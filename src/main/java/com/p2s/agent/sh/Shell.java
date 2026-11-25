package com.p2s.agent.sh;

import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.vfs.OverlayFs;
import com.p2s.agent.vfs.VfsException;
import com.p2s.agent.vfs.VfsPaths;
import com.p2s.agent.vfs.VirtualFs;
import com.p2s.agent.vfs.WorkspaceDocs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code sh} tool: a bash-like command line over the virtual file system (not an OS
 * shell). Supports pipes, {@code && || ;}, redirects {@code > >> < 2>/dev/null 2>&1}, quotes
 * and globs. Writes go to an overlay committed only when no command failed.
 */
public final class Shell {
    public static final long TIMEOUT_MS = 10_000;
    public static final int MAX_STREAM_CHARS = 4_000_000;
    public static final List<String> COMMANDS = List.of("ls", "cat", "head", "tail", "wc", "grep", "find", "sed", "sort",
            "uniq", "cut", "tr", "cp", "mv", "rm", "tee", "echo", "printf", "diff", "tomlfmt", "true", "false");

    private final OverlayFs fs;
    private final AbortSignal signal;
    private final long deadline;
    private final StringBuilder stderr = new StringBuilder();
    private boolean failed;

    private Shell(VirtualFs base, AbortSignal signal) {
        this.fs = new OverlayFs(base, MAX_STREAM_CHARS);
        this.signal = signal == null ? AbortSignal.none() : signal;
        this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
    }

    public record Result(int exitCode, String stdout, String stderr, boolean failed,
                         List<OverlayFs.CommitResult> commits) {
    }

    public static Result run(String commandLine, VirtualFs base, AbortSignal signal) {
        Shell shell = new Shell(base, signal);
        StringBuilder stdout = new StringBuilder();
        int status;
        try {
            List<Step> steps = parse(tokenize(commandLine));
            status = shell.execute(steps, stdout);
        } catch (ShellError | Stop ex) {
            shell.stderr.append("sh: ").append(ex.getMessage()).append('\n');
            shell.failed = true;
            status = 2;
        }
        List<OverlayFs.CommitResult> commits = List.of();
        if (!shell.failed && shell.fs.hasChanges()) {
            commits = shell.fs.commit();
            for (OverlayFs.CommitResult commit : commits) {
                if (!commit.ok()) {
                    shell.stderr.append("sh: ").append(commit.path()).append(": ").append(commit.message()).append('\n');
                    status = Math.max(status, 1);
                }
            }
        } else if (shell.failed && shell.fs.hasChanges()) {
            shell.stderr.append("sh: no files were changed because a command failed\n");
        }
        return new Result(status, stdout.toString(), shell.stderr.toString(), shell.failed, commits);
    }

    // ------------------------------------------------------------------ lexing and parsing

    record Token(String text, boolean op, boolean glob) {
    }

    static List<Token> tokenize(String line) {
        List<Token> tokens = new ArrayList<>();
        String s = line == null ? "" : line;
        int i = 0;
        StringBuilder word = null;
        boolean glob = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n' || c == ';') {
                flush(tokens, word, glob);
                word = null;
                glob = false;
                tokens.add(new Token(";", true, false));
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                flush(tokens, word, glob);
                word = null;
                glob = false;
                i++;
                continue;
            }
            if (c == '#' && word == null) {
                int end = s.indexOf('\n', i);
                i = end < 0 ? s.length() : end;
                continue;
            }
            if (c == '|' || c == '&' || c == '>' || c == '<') {
                if (c == '>' && word != null && word.toString().equals("2") && !glob) {
                    word = null;
                    if (s.startsWith(">&1", i)) {
                        tokens.add(new Token("2>&1", true, false));
                        i += 3;
                    } else {
                        tokens.add(new Token("2>", true, false));
                        i += 1;
                    }
                    continue;
                }
                flush(tokens, word, glob);
                word = null;
                glob = false;
                if (s.startsWith("&&", i) || s.startsWith("||", i) || s.startsWith(">>", i)) {
                    tokens.add(new Token(s.substring(i, i + 2), true, false));
                    i += 2;
                } else if (s.startsWith("<<", i)) {
                    throw new ShellError("here-documents (<<) are not supported; use printf or the script tool");
                } else if (c == '&') {
                    throw new ShellError("background jobs (&) are not supported");
                } else {
                    tokens.add(new Token(String.valueOf(c), true, false));
                    i++;
                }
                continue;
            }
            if (c == '(' || c == ')' || c == '`') {
                throw new ShellError("subshells and command substitution are not supported; use the script tool");
            }
            if (word == null) {
                word = new StringBuilder();
            }
            if (c == '\'') {
                int end = s.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new ShellError("unterminated single quote");
                }
                word.append(s, i + 1, end);
                i = end + 1;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (true) {
                    if (j >= s.length()) {
                        throw new ShellError("unterminated double quote");
                    }
                    char d = s.charAt(j);
                    if (d == '"') {
                        break;
                    }
                    if (d == '\\' && j + 1 < s.length() && "\"\\$`".indexOf(s.charAt(j + 1)) >= 0) {
                        word.append(s.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (d == '$' && isExpansion(s, j)) {
                        throw new ShellError("variables ($...) are not supported");
                    }
                    if (d == '`') {
                        throw new ShellError("command substitution is not supported; use the script tool");
                    }
                    word.append(d);
                    j++;
                }
                i = j + 1;
                continue;
            }
            if (c == '\\' && i + 1 < s.length()) {
                word.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '$' && isExpansion(s, i)) {
                throw new ShellError("variables ($...) are not supported");
            }
            if (c == '*' || c == '?') {
                glob = true;
            }
            word.append(c);
            i++;
        }
        flush(tokens, word, glob);
        return tokens;
    }

    private static boolean isExpansion(String s, int i) {
        if (i + 1 >= s.length()) {
            return false;
        }
        char next = s.charAt(i + 1);
        return Character.isLetter(next) || next == '_' || next == '{' || next == '(';
    }

    private static void flush(List<Token> tokens, StringBuilder word, boolean glob) {
        if (word != null) {
            tokens.add(new Token(word.toString(), false, glob));
        }
    }

    /** A pipeline and the connector that precedes it (";", "&&" or "||"). */
    record Step(String connector, List<Command> pipeline) {
    }

    record Command(List<Token> words, String stdinFile, String stdoutFile, boolean append, boolean dropStderr,
                   boolean mergeStderr) {
    }

    static List<Step> parse(List<Token> tokens) {
        List<Step> steps = new ArrayList<>();
        String connector = ";";
        List<Command> pipeline = new ArrayList<>();
        List<Token> words = new ArrayList<>();
        String in = null;
        String out = null;
        boolean append = false;
        boolean drop = false;
        boolean merge = false;
        for (int i = 0; i <= tokens.size(); i++) {
            Token token = i < tokens.size() ? tokens.get(i) : new Token(";", true, false);
            if (!token.op()) {
                words.add(token);
                continue;
            }
            switch (token.text()) {
                case ">", ">>", "<", "2>" -> {
                    if (i + 1 >= tokens.size() || tokens.get(i + 1).op()) {
                        throw new ShellError("missing file name after " + token.text());
                    }
                    String target = tokens.get(++i).text();
                    switch (token.text()) {
                        case "<" -> in = target;
                        case "2>" -> {
                            if (!target.equals("/dev/null")) {
                                throw new ShellError("only 2>/dev/null and 2>&1 are supported");
                            }
                            drop = true;
                        }
                        default -> {
                            out = target;
                            append = token.text().equals(">>");
                        }
                    }
                }
                case "2>&1" -> merge = true;
                case "|", ";", "&&", "||" -> {
                    if (words.isEmpty()) {
                        if (token.text().equals(";") && pipeline.isEmpty()) {
                            continue;
                        }
                        throw new ShellError("syntax error near '" + token.text() + "'");
                    }
                    pipeline.add(new Command(List.copyOf(words), in, out, append, drop, merge));
                    words.clear();
                    in = null;
                    out = null;
                    append = false;
                    drop = false;
                    merge = false;
                    if (!token.text().equals("|")) {
                        steps.add(new Step(connector, List.copyOf(pipeline)));
                        pipeline.clear();
                        connector = token.text();
                    }
                }
                default -> throw new ShellError("unsupported operator " + token.text());
            }
        }
        if (!pipeline.isEmpty()) {
            throw new ShellError("syntax error: command expected after '|'");
        }
        return steps;
    }

    // ------------------------------------------------------------------ execution

    private int execute(List<Step> steps, StringBuilder stdout) {
        int status = 0;
        for (Step step : steps) {
            if (step.connector().equals("&&") && status != 0) {
                continue;
            }
            if (step.connector().equals("||") && status == 0) {
                continue;
            }
            status = runPipeline(step.pipeline(), stdout);
        }
        return status;
    }

    private int runPipeline(List<Command> pipeline, StringBuilder stdout) {
        String input = null;
        int status = 0;
        for (Command command : pipeline) {
            check();
            if (command.stdinFile() != null) {
                input = readFile(command.stdinFile());
            }
            List<String> args = expand(command.words());
            String name = args.removeFirst();
            Out out;
            try {
                out = dispatch(name, args, input);
            } catch (ShellError ex) {
                failed = true;
                if (!command.dropStderr()) {
                    stderr.append(name).append(": ").append(ex.getMessage()).append('\n');
                }
                out = new Out("", 2);
            }
            if (out.text().length() > MAX_STREAM_CHARS) {
                failed = true;
                stderr.append(name).append(": output exceeds ").append(MAX_STREAM_CHARS).append(" characters\n");
                out = new Out("", 2);
            }
            String text = out.text();
            if (command.mergeStderr() && !out.err().isEmpty()) {
                text = text + out.err();
            } else if (!command.dropStderr()) {
                stderr.append(out.err());
            }
            if (command.stdoutFile() != null) {
                String path = command.stdoutFile();
                String previous = command.append() && fs.exists(path) ? readFile(path) : "";
                writeFile(path, previous + text);
                text = "";
            }
            input = text;
            status = out.status();
        }
        if (input != null) {
            stdout.append(input);
            if (stdout.length() > MAX_STREAM_CHARS) {
                throw new ShellError("output exceeds " + MAX_STREAM_CHARS + " characters");
            }
        }
        return status;
    }

    private void check() {
        if (signal.isAborted()) {
            throw new Stop("stopped");
        }
        if (System.nanoTime() > deadline) {
            throw new Stop("timed out after " + TIMEOUT_MS / 1000 + "s");
        }
    }

    /** Timeout or Stop: ends the whole command line, unlike a failing command. */
    private static final class Stop extends RuntimeException {
        Stop(String message) {
            super(message);
        }
    }

    private List<String> expand(List<Token> words) {
        List<String> out = new ArrayList<>();
        for (Token word : words) {
            if (!word.glob()) {
                out.add(word.text());
                continue;
            }
            List<String> matches = glob(word.text());
            if (matches.isEmpty()) {
                out.add(word.text());
            } else {
                out.addAll(matches);
            }
        }
        return out;
    }

    private List<String> glob(String pattern) {
        String normalized = pattern.replace('\\', '/');
        int firstGlob = Math.min(idx(normalized, '*'), idx(normalized, '?'));
        String fixed = normalized.substring(0, firstGlob);
        String dir = fixed.contains("/") ? fixed.substring(0, fixed.lastIndexOf('/')) : "";
        Set<String> candidates = new TreeSet<>();
        try {
            collect(dir, candidates, 0);
        } catch (VfsException | ShellError ex) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (VfsPaths.globMatches(normalized, candidate)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static int idx(String s, char c) {
        int i = s.indexOf(c);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private void collect(String dir, Set<String> out, int depth) throws VfsException {
        if (depth > 16) {
            return;
        }
        for (VirtualFs.Entry entry : fs.list(dir)) {
            out.add(entry.path());
            if (entry.directory()) {
                collect(entry.path(), out, depth + 1);
            }
        }
    }

    record Out(String text, int status, String err) {
        Out(String text, int status) {
            this(text, status, "");
        }
    }

    private Out dispatch(String name, List<String> args, String stdin) {
        return switch (name) {
            case "echo" -> echo(args);
            case "printf" -> printf(args);
            case "ls" -> ls(args);
            case "cat" -> new Out(String.join("", inputs(files(args), stdin)), 0);
            case "head" -> headTail(args, stdin, true);
            case "tail" -> headTail(args, stdin, false);
            case "wc" -> wc(args, stdin);
            case "grep" -> grep(args, stdin);
            case "find" -> find(args);
            case "sed" -> sed(args, stdin);
            case "sort" -> sort(args, stdin);
            case "uniq" -> uniq(args, stdin);
            case "cut" -> cut(args, stdin);
            case "tr" -> tr(args, stdin);
            case "cp" -> cp(args);
            case "mv" -> mv(args);
            case "rm" -> rm(args);
            case "tee" -> tee(args, stdin);
            case "diff" -> diff(args);
            case "tomlfmt" -> tomlfmt(args);
            case "true" -> new Out("", 0);
            case "false" -> new Out("", 1);
            case "cd", "pwd", "export", "source", "bash", "sh", "python", "python3", "node", "awk", "xargs", "curl", "wget" ->
                    throw new ShellError("not available here. Supported commands: " + String.join(" ", COMMANDS)
                            + ". For logic or loops use the script tool");
            default -> throw new ShellError("command not found. Supported commands: " + String.join(" ", COMMANDS));
        };
    }

    // ------------------------------------------------------------------ file helpers

    private String readFile(String path) {
        try {
            if (fs.isDirectory(path)) {
                throw new ShellError(path + ": Is a directory");
            }
            return fs.read(path);
        } catch (VfsException ex) {
            throw new ShellError(ex.getMessage());
        }
    }

    private void writeFile(String path, String content) {
        try {
            if (VfsPaths.isWorkspaceFile(VfsPaths.normalize(path, false))) {
                // Reject invalid workspace TOML before it reaches the staging area.
                WorkspaceDocs.validate(content);
            }
            fs.write(path, content);
        } catch (VfsException ex) {
            throw new ShellError(ex.getMessage());
        }
    }

    private static List<String> files(List<String> args) {
        List<String> files = new ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("-") || arg.equals("-")) {
                files.add(arg);
            }
        }
        return files;
    }

    private List<String> inputs(List<String> files, String stdin) {
        if (files.isEmpty()) {
            return List.of(stdin == null ? "" : stdin);
        }
        List<String> out = new ArrayList<>();
        for (String file : files) {
            out.add(file.equals("-") ? (stdin == null ? "" : stdin) : readFile(file));
        }
        return out;
    }

    static List<String> lines(String text) {
        return TextDiff.lines(text);
    }

    static String join(List<String> lines, boolean trailingNewline) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + (trailingNewline ? "\n" : "");
    }

    /** Short options like -n5, -in and long forms; returns remaining operands. */
    private static final class Opts {
        final Set<Character> flags = new LinkedHashSet<>();
        final Map<Character, String> values = new LinkedHashMap<>();
        final List<String> operands = new ArrayList<>();
        final List<String> multi = new ArrayList<>();

        Opts(List<String> args, String withValue, String allowed, char multiFlag) {
            boolean endOfOptions = false;
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (endOfOptions || !arg.startsWith("-") || arg.equals("-") || arg.length() == 1) {
                    operands.add(arg);
                    continue;
                }
                if (arg.equals("--")) {
                    endOfOptions = true;
                    continue;
                }
                if (arg.matches("-\\d+") && withValue.indexOf('n') >= 0) {
                    values.put('n', arg.substring(1));
                    continue;
                }
                for (int k = 1; k < arg.length(); k++) {
                    char f = arg.charAt(k);
                    if (withValue.indexOf(f) >= 0) {
                        String value = k + 1 < arg.length() ? arg.substring(k + 1) : (i + 1 < args.size() ? args.get(++i) : null);
                        if (value == null) {
                            throw new ShellError("option -" + f + " requires a value");
                        }
                        if (f == multiFlag) {
                            multi.add(value);
                        } else {
                            values.put(f, value);
                        }
                        break;
                    }
                    if (allowed.indexOf(f) < 0) {
                        throw new ShellError("unsupported option -" + f);
                    }
                    flags.add(f);
                }
            }
        }

        boolean has(char f) {
            return flags.contains(f);
        }

        int intValue(char f, int fallback) {
            String v = values.get(f);
            if (v == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
            } catch (NumberFormatException ex) {
                throw new ShellError("invalid number '" + v + "'");
            }
        }
    }

    // ------------------------------------------------------------------ commands

    private Out echo(List<String> args) {
        boolean newline = true;
        boolean escapes = false;
        int start = 0;
        while (start < args.size() && args.get(start).matches("-[neE]+")) {
            String flag = args.get(start);
            newline &= !flag.contains("n");
            escapes |= flag.contains("e");
            start++;
        }
        String text = String.join(" ", args.subList(start, args.size()));
        if (escapes) {
            text = unescape(text);
        }
        return new Out(text + (newline ? "\n" : ""), 0);
    }

    private Out printf(List<String> args) {
        if (args.isEmpty()) {
            throw new ShellError("usage: printf FORMAT [ARG...]");
        }
        String format = unescape(args.getFirst());
        List<String> values = args.subList(1, args.size());
        StringBuilder out = new StringBuilder();
        int used = 0;
        do {
            int consumed = 0;
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c != '%' || i + 1 >= format.length()) {
                    out.append(c);
                    continue;
                }
                char spec = format.charAt(++i);
                if (spec == '%') {
                    out.append('%');
                    continue;
                }
                String value = used + consumed < values.size() ? values.get(used + consumed) : "";
                consumed++;
                switch (spec) {
                    case 's' -> out.append(value);
                    case 'd', 'i' -> {
                        try {
                            out.append(value.isEmpty() ? 0 : Long.parseLong(value.trim()));
                        } catch (NumberFormatException ex) {
                            throw new ShellError("invalid number '" + value + "'");
                        }
                    }
                    default -> throw new ShellError("unsupported format %" + spec + " (use %s, %d, %%)");
                }
            }
            used += consumed;
            if (consumed == 0) {
                break;
            }
        } while (used < values.size());
        return new Out(out.toString(), 0);
    }

    static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char n = text.charAt(++i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    default -> out.append('\\').append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private Out ls(List<String> args) {
        Opts opts = new Opts(args, "", "laR1", '\0');
        List<String> targets = opts.operands.isEmpty() ? List.of("") : opts.operands;
        StringBuilder out = new StringBuilder();
        for (String target : targets) {
            try {
                if (!target.isEmpty() && !fs.isDirectory(target)) {
                    if (!fs.exists(target)) {
                        throw new ShellError(target + ": No such file or directory");
                    }
                    out.append(VfsPaths.normalize(target, false)).append('\n');
                    continue;
                }
                List<VirtualFs.Entry> entries = new ArrayList<>(fs.list(target));
                entries.sort(Comparator.comparing(VirtualFs.Entry::path));
                if (targets.size() > 1) {
                    out.append(target.isEmpty() ? "." : target).append(":\n");
                }
                for (VirtualFs.Entry entry : entries) {
                    out.append(entry.path()).append(entry.directory() ? "/" : "").append('\n');
                    if (opts.has('R') && entry.directory()) {
                        for (String file : fs.walk(entry.path())) {
                            out.append(file).append('\n');
                        }
                    }
                }
            } catch (VfsException ex) {
                throw new ShellError(ex.getMessage());
            }
        }
        return new Out(out.toString(), 0);
    }

    private Out headTail(List<String> args, String stdin, boolean head) {
        Opts opts = new Opts(args, "n", "q", '\0');
        String raw = opts.values.getOrDefault('n', "10");
        boolean fromStart = !head && raw.startsWith("+");
        int n = opts.intValue('n', 10);
        StringBuilder out = new StringBuilder();
        for (String text : inputs(opts.operands, stdin)) {
            List<String> lines = lines(text);
            List<String> picked;
            if (head) {
                picked = n >= 0 ? lines.subList(0, Math.min(n, lines.size())) : lines.subList(0, Math.max(0, lines.size() + n));
            } else if (fromStart) {
                picked = lines.subList(Math.min(lines.size(), Math.max(0, n - 1)), lines.size());
            } else {
                picked = lines.subList(Math.max(0, lines.size() - n), lines.size());
            }
            out.append(join(picked, true));
        }
        return new Out(out.toString(), 0);
    }

    private Out wc(List<String> args, String stdin) {
        Opts opts = new Opts(args, "", "lwc", '\0');
        boolean all = !opts.has('l') && !opts.has('w') && !opts.has('c');
        List<String> names = opts.operands;
        List<String> texts = inputs(names, stdin);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            List<String> parts = new ArrayList<>();
            if (all || opts.has('l')) {
                parts.add(String.valueOf(text.chars().filter(ch -> ch == '\n').count()));
            }
            if (all || opts.has('w')) {
                parts.add(String.valueOf(text.isBlank() ? 0 : text.trim().split("\\s+").length));
            }
            if (all || opts.has('c')) {
                parts.add(String.valueOf(text.length()));
            }
            out.append(String.join(" ", parts));
            if (!names.isEmpty()) {
                out.append(' ').append(names.get(i));
            }
            out.append('\n');
        }
        return new Out(out.toString(), 0);
    }

    private Out grep(List<String> args, String stdin) {
        Opts opts = new Opts(args, "em", "nivcloEFrRhHwq", 'e');
        List<String> patterns = new ArrayList<>(opts.multi);
        List<String> operands = new ArrayList<>(opts.operands);
        if (patterns.isEmpty()) {
            if (operands.isEmpty()) {
                throw new ShellError("usage: grep [OPTIONS] PATTERN [FILE...]");
            }
            patterns.add(operands.removeFirst());
        }
        boolean recursive = opts.has('r') || opts.has('R');
        List<Pattern> compiled = new ArrayList<>();
        for (String p : patterns) {
            compiled.add(PosixRegex.compile(p, opts.has('E'), opts.has('F'), opts.has('i'), opts.has('w')));
        }
        int max = opts.intValue('m', Integer.MAX_VALUE);
        List<String[]> sources = new ArrayList<>();
        if (operands.isEmpty()) {
            if (recursive) {
                operands.add("workspace");
            } else {
                sources.add(new String[]{"(standard input)", stdin == null ? "" : stdin});
            }
        }
        for (String operand : operands) {
            if (fs.isDirectory(operand)) {
                if (!recursive) {
                    throw new ShellError(operand + ": Is a directory (use -r)");
                }
                try {
                    for (String file : fs.walk(operand)) {
                        sources.add(new String[]{file, readFile(file)});
                    }
                } catch (VfsException ex) {
                    throw new ShellError(ex.getMessage());
                }
            } else {
                sources.add(new String[]{operand, readFile(operand)});
            }
        }
        boolean showNames = opts.has('H') || (!opts.has('h') && (sources.size() > 1 || recursive));
        StringBuilder out = new StringBuilder();
        boolean any = false;
        for (String[] source : sources) {
            check();
            int count = 0;
            List<String> lines = lines(source[1]);
            for (int n = 0; n < lines.size() && count < max; n++) {
                String line = lines.get(n);
                boolean matched = false;
                List<String> pieces = new ArrayList<>();
                for (Pattern pattern : compiled) {
                    Matcher matcher = pattern.matcher(line);
                    while (matcher.find()) {
                        matched = true;
                        if (!opts.has('o')) {
                            break;
                        }
                        if (matcher.end() > matcher.start()) {
                            pieces.add(matcher.group());
                        }
                    }
                }
                if (matched == opts.has('v')) {
                    continue;
                }
                count++;
                any = true;
                if (opts.has('c') || opts.has('l') || opts.has('q')) {
                    continue;
                }
                String prefix = (showNames ? source[0] + ":" : "") + (opts.has('n') ? (n + 1) + ":" : "");
                if (opts.has('o') && !opts.has('v')) {
                    for (String piece : pieces) {
                        out.append(prefix).append(piece).append('\n');
                    }
                } else {
                    out.append(prefix).append(line).append('\n');
                }
            }
            if (opts.has('c')) {
                out.append(showNames ? source[0] + ":" : "").append(count).append('\n');
            } else if (opts.has('l') && count > 0) {
                out.append(source[0]).append('\n');
            }
        }
        return new Out(opts.has('q') ? "" : out.toString(), any ? 0 : 1);
    }

    private Out find(List<String> args) {
        String root = "";
        String name = null;
        String type = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "-name", "-iname" -> {
                    if (i + 1 >= args.size()) {
                        throw new ShellError("missing argument to " + arg);
                    }
                    name = args.get(++i);
                }
                case "-type" -> {
                    if (i + 1 >= args.size()) {
                        throw new ShellError("missing argument to -type");
                    }
                    type = args.get(++i);
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new ShellError("unsupported predicate " + arg + " (use -name, -type)");
                    }
                    root = arg.equals(".") ? "" : arg;
                }
            }
        }
        Set<String> all = new TreeSet<>();
        try {
            collect(root, all, 0);
        } catch (VfsException ex) {
            throw new ShellError(ex.getMessage());
        }
        StringBuilder out = new StringBuilder();
        String regex = name == null ? null : VfsPaths.globToRegex(name);
        for (String path : all) {
            boolean dir = fs.isDirectory(path);
            if ("f".equals(type) && dir || "d".equals(type) && !dir) {
                continue;
            }
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (regex != null && !leaf.toLowerCase(Locale.ROOT).matches(regex.toLowerCase(Locale.ROOT))
                    && !leaf.matches(regex)) {
                continue;
            }
            out.append(path).append('\n');
        }
        return new Out(out.toString(), 0);
    }

    private Out sort(List<String> args, String stdin) {
        Opts opts = new Opts(args, "", "rnu", '\0');
        List<String> lines = new ArrayList<>();
        for (String text : inputs(opts.operands, stdin)) {
            lines.addAll(lines(text));
        }
        Comparator<String> order = opts.has('n')
                ? Comparator.comparingDouble(Shell::leadingNumber).thenComparing(Comparator.naturalOrder())
                : Comparator.naturalOrder();
        lines.sort(opts.has('r') ? order.reversed() : order);
        if (opts.has('u')) {
            lines = new ArrayList<>(new LinkedHashSet<>(lines));
        }
        return new Out(join(lines, true), 0);
    }

    private static double leadingNumber(String line) {
        Matcher m = Pattern.compile("^\\s*(-?\\d+(\\.\\d+)?)").matcher(line);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    private Out uniq(List<String> args, String stdin) {
        Opts opts = new Opts(args, "", "cdu", '\0');
        List<String> lines = lines(inputs(opts.operands, stdin).getFirst());
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.size()) {
            int j = i;
            while (j < lines.size() && lines.get(j).equals(lines.get(i))) {
                j++;
            }
            int count = j - i;
            if ((!opts.has('d') || count > 1) && (!opts.has('u') || count == 1)) {
                out.append(opts.has('c') ? String.format("%7d ", count) : "").append(lines.get(i)).append('\n');
            }
            i = j;
        }
        return new Out(out.toString(), 0);
    }

    private Out cut(List<String> args, String stdin) {
        Opts opts = new Opts(args, "dfc", "", '\0');
        String delimiter = opts.values.getOrDefault('d', "\t");
        if (delimiter.length() != 1) {
            throw new ShellError("the delimiter must be a single character");
        }
        boolean byChars = opts.values.containsKey('c');
        String list = opts.values.get(byChars ? 'c' : 'f');
        if (list == null) {
            throw new ShellError("you must specify -f or -c");
        }
        List<int[]> ranges = parseRanges(list);
        StringBuilder out = new StringBuilder();
        for (String line : lines(inputs(opts.operands, stdin).getFirst())) {
            if (byChars) {
                StringBuilder picked = new StringBuilder();
                for (int i = 0; i < line.length(); i++) {
                    if (inRanges(ranges, i + 1)) {
                        picked.append(line.charAt(i));
                    }
                }
                out.append(picked).append('\n');
            } else if (!line.contains(delimiter)) {
                out.append(line).append('\n');
            } else {
                String[] fields = line.split(Pattern.quote(delimiter), -1);
                List<String> picked = new ArrayList<>();
                for (int i = 0; i < fields.length; i++) {
                    if (inRanges(ranges, i + 1)) {
                        picked.add(fields[i]);
                    }
                }
                out.append(String.join(delimiter, picked)).append('\n');
            }
        }
        return new Out(out.toString(), 0);
    }

    private static List<int[]> parseRanges(String list) {
        List<int[]> ranges = new ArrayList<>();
        for (String part : list.split(",")) {
            try {
                if (part.contains("-")) {
                    String[] bounds = part.split("-", -1);
                    int lo = bounds[0].isEmpty() ? 1 : Integer.parseInt(bounds[0]);
                    int hi = bounds[1].isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(bounds[1]);
                    ranges.add(new int[]{lo, hi});
                } else {
                    int v = Integer.parseInt(part);
                    ranges.add(new int[]{v, v});
                }
            } catch (NumberFormatException ex) {
                throw new ShellError("invalid list '" + list + "'");
            }
        }
        return ranges;
    }

    private static boolean inRanges(List<int[]> ranges, int position) {
        for (int[] range : ranges) {
            if (position >= range[0] && position <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private Out tr(List<String> args, String stdin) {
        Opts opts = new Opts(args, "", "d", '\0');
        if (opts.operands.isEmpty() || (!opts.has('d') && opts.operands.size() < 2)) {
            throw new ShellError("usage: tr [-d] SET1 [SET2]");
        }
        String from = expandSet(opts.operands.get(0));
        String to = opts.has('d') ? "" : expandSet(opts.operands.get(1));
        String text = stdin == null ? "" : stdin;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int k = from.indexOf(c);
            if (k < 0) {
                out.append(c);
            } else if (!opts.has('d')) {
                out.append(to.isEmpty() ? c : to.charAt(Math.min(k, to.length() - 1)));
            }
        }
        return new Out(out.toString(), 0);
    }

    private static String expandSet(String set) {
        String s = unescape(set);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i + 2 < s.length() && s.charAt(i + 1) == '-') {
                for (char c = s.charAt(i); c <= s.charAt(i + 2); c++) {
                    out.append(c);
                }
                i += 2;
            } else {
                out.append(s.charAt(i));
            }
        }
        return out.toString();
    }

    private Out cp(List<String> args) {
        List<String> operands = files(args);
        if (operands.size() != 2) {
            throw new ShellError("usage: cp SOURCE TARGET");
        }
        writeFile(operands.get(1), readFile(operands.get(0)));
        return new Out("", 0);
    }

    private Out mv(List<String> args) {
        List<String> operands = files(args);
        if (operands.size() != 2) {
            throw new ShellError("usage: mv SOURCE TARGET");
        }
        try {
            fs.move(operands.get(0), operands.get(1));
        } catch (VfsException ex) {
            throw new ShellError(ex.getMessage());
        }
        return new Out("", 0);
    }

    private Out rm(List<String> args) {
        Opts opts = new Opts(args, "", "fr", '\0');
        for (String path : opts.operands) {
            try {
                if (!fs.exists(path)) {
                    if (opts.has('f')) {
                        continue;
                    }
                    throw new ShellError(path + ": No such file");
                }
                if (fs.isDirectory(path)) {
                    throw new ShellError(path + ": Is a directory; remove files individually");
                }
                fs.delete(path);
            } catch (VfsException ex) {
                throw new ShellError(ex.getMessage());
            }
        }
        return new Out("", 0);
    }

    private Out tee(List<String> args, String stdin) {
        Opts opts = new Opts(args, "", "a", '\0');
        String text = stdin == null ? "" : stdin;
        for (String path : opts.operands) {
            String previous = opts.has('a') && fs.exists(path) ? readFile(path) : "";
            writeFile(path, previous + text);
        }
        return new Out(text, 0);
    }

    private Out diff(List<String> args) {
        List<String> operands = files(args);
        try {
            if (operands.size() == 1) {
                String path = VfsPaths.normalize(operands.getFirst(), false);
                String committed;
                try {
                    committed = fs.readCommitted(path);
                } catch (VfsException ex) {
                    committed = "";
                }
                String current = readFile(path);
                String diff = TextDiff.unified(path + " (committed)", path + " (staged)", committed, current, 3);
                return new Out(diff, diff.isEmpty() ? 0 : 1);
            }
            if (operands.size() == 2) {
                String diff = TextDiff.unified(operands.get(0), operands.get(1), readFile(operands.get(0)),
                        readFile(operands.get(1)), 3);
                return new Out(diff, diff.isEmpty() ? 0 : 1);
            }
        } catch (VfsException ex) {
            throw new ShellError(ex.getMessage());
        }
        throw new ShellError("usage: diff FILE (staged vs committed) | diff FILE1 FILE2");
    }

    private Out tomlfmt(List<String> args) {
        Opts opts = new Opts(args, "", "i", '\0');
        if (opts.operands.size() != 1) {
            throw new ShellError("usage: tomlfmt [-i] WORKSPACE_FILE");
        }
        String path = opts.operands.getFirst();
        try {
            String formatted = WorkspaceDocs.print(WorkspaceDocs.parse(readFile(path)));
            if (opts.has('i')) {
                writeFile(path, formatted);
                return new Out("", 0);
            }
            return new Out(formatted, 0);
        } catch (VfsException ex) {
            throw new ShellError(ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ sed

    private Out sed(List<String> args, String stdin) {
        boolean quiet = false;
        boolean inPlace = false;
        boolean extended = false;
        List<String> scripts = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-e")) {
                if (i + 1 >= args.size()) {
                    throw new ShellError("option -e requires a script");
                }
                scripts.add(args.get(++i));
            } else if (arg.startsWith("-") && arg.length() > 1 && !arg.equals("-")) {
                for (char f : arg.substring(1).toCharArray()) {
                    switch (f) {
                        case 'n' -> quiet = true;
                        case 'i' -> inPlace = true;
                        case 'E', 'r' -> extended = true;
                        default -> throw new ShellError("unsupported option -" + f);
                    }
                }
            } else if (scripts.isEmpty()) {
                scripts.add(arg);
            } else {
                files.add(arg);
            }
        }
        if (scripts.isEmpty()) {
            throw new ShellError("usage: sed [-n] [-i] [-E] SCRIPT [FILE...]");
        }
        List<SedCommand> program = new ArrayList<>();
        for (String script : scripts) {
            program.addAll(parseSed(script, extended));
        }
        if (inPlace) {
            if (files.isEmpty()) {
                throw new ShellError("-i requires a file");
            }
            for (String file : files) {
                String original = readFile(file);
                writeFile(file, runSed(program, original, quiet));
            }
            return new Out("", 0);
        }
        StringBuilder out = new StringBuilder();
        for (String text : inputs(files, stdin)) {
            out.append(runSed(program, text, quiet));
        }
        return new Out(out.toString(), 0);
    }

    private record Address(int line, boolean last, Pattern pattern) {
        boolean matches(int number, boolean isLast, String text) {
            if (last) {
                return isLast;
            }
            if (pattern != null) {
                return pattern.matcher(text).find();
            }
            return number == line;
        }
    }

    private static final class SedCommand {
        Address from;
        Address to;
        boolean negate;
        char name;
        Pattern regex;
        String replacement;
        boolean global;
        int occurrence = 1;
        boolean print;
        boolean active;
    }

    private static List<SedCommand> parseSed(String script, boolean extended) {
        List<SedCommand> commands = new ArrayList<>();
        int[] pos = {0};
        String s = script;
        while (true) {
            skipSedSeparators(s, pos);
            if (pos[0] >= s.length()) {
                break;
            }
            SedCommand command = new SedCommand();
            command.from = parseAddress(s, pos, extended);
            if (command.from != null && pos[0] < s.length() && s.charAt(pos[0]) == ',') {
                pos[0]++;
                command.to = parseAddress(s, pos, extended);
                if (command.to == null) {
                    throw new ShellError("sed: missing address after ','");
                }
            }
            while (pos[0] < s.length() && s.charAt(pos[0]) == ' ') {
                pos[0]++;
            }
            if (pos[0] < s.length() && s.charAt(pos[0]) == '!') {
                command.negate = true;
                pos[0]++;
            }
            if (pos[0] >= s.length()) {
                throw new ShellError("sed: missing command");
            }
            command.name = s.charAt(pos[0]++);
            switch (command.name) {
                case 's' -> {
                    if (pos[0] >= s.length()) {
                        throw new ShellError("sed: unterminated s command");
                    }
                    char delimiter = s.charAt(pos[0]++);
                    String pattern = readUntil(s, pos, delimiter);
                    String replacement = readUntil(s, pos, delimiter);
                    boolean ignoreCase = false;
                    while (pos[0] < s.length() && "gipI0123456789".indexOf(s.charAt(pos[0])) >= 0) {
                        char flag = s.charAt(pos[0]++);
                        if (flag == 'g') {
                            command.global = true;
                        } else if (flag == 'i' || flag == 'I') {
                            ignoreCase = true;
                        } else if (flag == 'p') {
                            command.print = true;
                        } else {
                            command.occurrence = flag - '0';
                        }
                    }
                    command.regex = PosixRegex.compile(pattern, extended, false, ignoreCase, false);
                    command.replacement = PosixRegex.replacement(replacement);
                }
                case 'p', 'd', 'q', '=' -> {
                }
                default -> throw new ShellError("sed: unsupported command '" + command.name + "' (supported: s p d q =)");
            }
            commands.add(command);
        }
        return commands;
    }

    private static void skipSedSeparators(String s, int[] pos) {
        while (pos[0] < s.length() && (s.charAt(pos[0]) == ';' || Character.isWhitespace(s.charAt(pos[0])))) {
            pos[0]++;
        }
    }

    private static Address parseAddress(String s, int[] pos, boolean extended) {
        if (pos[0] >= s.length()) {
            return null;
        }
        char c = s.charAt(pos[0]);
        if (c == '$') {
            pos[0]++;
            return new Address(0, true, null);
        }
        if (Character.isDigit(c)) {
            int start = pos[0];
            while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
                pos[0]++;
            }
            return new Address(Integer.parseInt(s.substring(start, pos[0])), false, null);
        }
        if (c == '/') {
            pos[0]++;
            String pattern = readUntil(s, pos, '/');
            return new Address(0, false, PosixRegex.compile(pattern, extended, false, false, false));
        }
        return null;
    }

    private static String readUntil(String s, int[] pos, char delimiter) {
        StringBuilder out = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '\\' && pos[0] + 1 < s.length()) {
                char next = s.charAt(pos[0] + 1);
                if (next == delimiter) {
                    out.append(delimiter);
                } else {
                    out.append(c).append(next);
                }
                pos[0] += 2;
                continue;
            }
            if (c == delimiter) {
                pos[0]++;
                return out.toString();
            }
            out.append(c);
            pos[0]++;
        }
        throw new ShellError("sed: unterminated expression");
    }

    private String runSed(List<SedCommand> program, String text, boolean quiet) {
        for (SedCommand command : program) {
            command.active = false;
        }
        List<String> lines = lines(text);
        boolean trailing = text.endsWith("\n");
        List<String> out = new ArrayList<>();
        outer:
        for (int n = 0; n < lines.size(); n++) {
            if ((n & 1023) == 0) {
                check();
            }
            String line = lines.get(n);
            boolean isLast = n == lines.size() - 1;
            boolean deleted = false;
            for (SedCommand command : program) {
                if (!selected(command, n + 1, isLast, line)) {
                    continue;
                }
                switch (command.name) {
                    case 's' -> {
                        String replaced = substitute(command, line);
                        if (!replaced.equals(line) || command.regex.matcher(line).find()) {
                            line = replaced;
                            if (command.print) {
                                out.add(line);
                            }
                        }
                    }
                    case 'p' -> out.add(line);
                    case '=' -> out.add(String.valueOf(n + 1));
                    case 'd' -> {
                        deleted = true;
                    }
                    case 'q' -> {
                        if (!quiet) {
                            out.add(line);
                        }
                        break outer;
                    }
                    default -> {
                    }
                }
                if (deleted) {
                    break;
                }
            }
            if (!deleted && !quiet) {
                out.add(line);
            }
        }
        return join(out, trailing);
    }

    private static boolean selected(SedCommand command, int number, boolean isLast, String line) {
        boolean match;
        if (command.from == null) {
            match = true;
        } else if (command.to == null) {
            match = command.from.matches(number, isLast, line);
        } else if (command.active) {
            match = true;
            if (command.to.matches(number, isLast, line) || (command.to.pattern == null && !command.to.last
                    && number >= command.to.line)) {
                command.active = false;
            }
        } else if (command.from.matches(number, isLast, line)) {
            match = true;
            boolean endsHere = command.to.pattern == null && !command.to.last && command.to.line <= number;
            command.active = !endsHere;
        } else {
            match = false;
        }
        return command.negate != match;
    }

    private static String substitute(SedCommand command, String line) {
        Matcher matcher = command.regex.matcher(line);
        StringBuilder out = new StringBuilder();
        int count = 0;
        int last = 0;
        while (matcher.find()) {
            count++;
            if (command.global ? count >= command.occurrence : count == command.occurrence) {
                out.append(line, last, matcher.start());
                String replacement = expandReplacement(matcher, command.replacement);
                out.append(replacement);
                last = matcher.end();
                if (!command.global) {
                    break;
                }
            }
            if (matcher.end() == matcher.start()) {
                if (matcher.end() >= line.length()) {
                    break;
                }
            }
        }
        out.append(line.substring(last));
        return out.toString();
    }

    /** Applies a Java-style replacement ($0, $1, escaped \$ and \\) for the current match. */
    private static String expandReplacement(Matcher matcher, String replacement) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\' && i + 1 < replacement.length()) {
                out.append(replacement.charAt(++i));
            } else if (c == '$' && i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
                int group = replacement.charAt(++i) - '0';
                if (group > matcher.groupCount()) {
                    throw new ShellError("sed: invalid reference \\" + group + " on s command's RHS");
                }
                String value = matcher.group(group);
                out.append(value == null ? "" : value);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** For tests and the tool description. */
    public static List<String> supportedCommands() {
        return Collections.unmodifiableList(COMMANDS);
    }
}
