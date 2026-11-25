package com.p2s.agent.script;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.ForLoop;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Loop;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.UpdateExpression;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes model-written JavaScript safe to run on Rhino 1.9, whose ES6 support silently
 * diverges from the spec in two places (verified against 1.9.2):
 * <ul>
 *   <li>{@code const} inside a loop body keeps its first value; {@code for (const x of ...)}
 *       inside a function is a syntax error. Every {@code const} becomes {@code let}, which
 *       Rhino scopes per iteration correctly.</li>
 *   <li>Variables declared in a loop head are shared by all iterations, so closures created in
 *       the body see the last value. When the body contains a closure that names such a
 *       variable, the head variable is renamed and re-declared at the top of the body. When
 *       the body also assigns the variable the script is rejected instead.</li>
 * </ul>
 * {@code async} and {@code await} are removed because the script API is synchronous.
 * Rewrites never add line breaks, so error line numbers still match the original source.
 */
public final class ScriptSourceRewriter {
    private static final String PREFIX = "$p2s_";

    private ScriptSourceRewriter() {
    }

    public static final class RejectedScriptException extends Exception {
        public RejectedScriptException(String message) {
            super(message);
        }
    }

    public static String rewrite(String source) throws RejectedScriptException {
        // Scripts are function bodies (top-level return is allowed), so parse them wrapped the
        // same way the sandbox runs them; the wrapper itself is never edited.
        String lexed = WRAP_OPEN + rewriteKeywords(source) + WRAP_CLOSE;
        AstRoot root;
        try {
            CompilerEnvirons env = new CompilerEnvirons();
            env.setLanguageVersion(Context.VERSION_ES6);
            env.setRecordingComments(false);
            env.setIdeMode(false);
            root = new Parser(env).parse(lexed, "script", 1);
        } catch (EvaluatorException ex) {
            // Let evaluation report the syntax error with Rhino's message and line.
            return unwrap(lexed);
        }
        List<TextEdit> edits = new ArrayList<>();
        int[] counter = {0};
        List<String> rejections = new ArrayList<>();
        root.visit(node -> {
            if (node instanceof ForInLoop loop) {
                planForIn(lexed, loop, edits, counter, rejections);
            } else if (node instanceof ForLoop loop) {
                planFor(lexed, loop, edits, counter, rejections);
            }
            return true;
        });
        if (!rejections.isEmpty()) {
            throw new RejectedScriptException(String.join("; ", rejections));
        }
        return unwrap(apply(lexed, edits));
    }

    private static final String WRAP_OPEN = "(function(){ ";
    private static final String WRAP_CLOSE = "\n})";

    private static String unwrap(String wrapped) {
        return wrapped.substring(WRAP_OPEN.length(), wrapped.length() - WRAP_CLOSE.length());
    }

    // ------------------------------------------------------------------ keywords

    /** Replaces const with let and drops async/await outside strings, comments and regexes. */
    static String rewriteKeywords(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        char lastSignificant = 0;
        String lastWord = "";
        List<Integer> templateDepth = new ArrayList<>();
        int braceDepth = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(src, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(src, i, end);
                i = end;
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = skipString(src, i, c);
                out.append(src, i, end);
                i = end;
                lastSignificant = c;
                lastWord = "";
                continue;
            }
            if (c == '`' || (c == '}' && !templateDepth.isEmpty() && templateDepth.getLast() == braceDepth)) {
                if (c == '}') {
                    templateDepth.removeLast();
                }
                // Template text up to the closing backtick or the next ${.
                int j = i + 1;
                out.append(c);
                while (j < n) {
                    char t = src.charAt(j);
                    if (t == '\\' && j + 1 < n) {
                        out.append(t).append(src.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (t == '`') {
                        out.append(t);
                        j++;
                        break;
                    }
                    if (t == '$' && j + 1 < n && src.charAt(j + 1) == '{') {
                        out.append("${");
                        j += 2;
                        templateDepth.add(braceDepth);
                        break;
                    }
                    out.append(t);
                    j++;
                }
                i = j;
                lastSignificant = '`';
                lastWord = "";
                continue;
            }
            if (c == '/' && regexAllowed(lastSignificant, lastWord)) {
                int end = skipRegex(src, i);
                out.append(src, i, end);
                i = end;
                lastSignificant = 'r';
                lastWord = "";
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(src.charAt(end))) {
                    end++;
                }
                String word = src.substring(i, end);
                boolean propertyName = lastSignificant == '.';
                if (!propertyName && word.equals("const")) {
                    out.append("let  ");
                } else if (!propertyName && word.equals("await") && isAwaitOperator(src, end)) {
                    out.append("     ");
                } else if (!propertyName && word.equals("async") && isAsyncModifier(src, end)) {
                    out.append("     ");
                } else {
                    out.append(word);
                }
                i = end;
                lastSignificant = 'a';
                lastWord = word;
                continue;
            }
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
            }
            out.append(c);
            if (!Character.isWhitespace(c)) {
                lastSignificant = c;
                lastWord = "";
            }
            i++;
        }
        return out.toString();
    }

    private static boolean isAwaitOperator(String src, int end) {
        int j = skipSpaces(src, end);
        if (j >= src.length()) {
            return false;
        }
        char next = src.charAt(j);
        // "await" used as a plain identifier is followed by an operator or punctuation.
        return Character.isJavaIdentifierStart(next) || next == '(' || next == '[' || next == '\'' || next == '"'
                || next == '`' || next == '!' || next == '-' || next == '+' || next == '{';
    }

    private static boolean isAsyncModifier(String src, int end) {
        int j = skipSpaces(src, end);
        if (j >= src.length()) {
            return false;
        }
        if (src.startsWith("function", j)) {
            return true;
        }
        char next = src.charAt(j);
        if (next == '(') {
            // async (a, b) => ...
            int close = matchParen(src, j);
            return close > 0 && src.startsWith("=>", skipSpaces(src, close + 1));
        }
        if (Character.isJavaIdentifierStart(next)) {
            int k = j;
            while (k < src.length() && Character.isJavaIdentifierPart(src.charAt(k))) {
                k++;
            }
            return src.startsWith("=>", skipSpaces(src, k));
        }
        return false;
    }

    private static int matchParen(String src, int open) {
        int depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return k;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                k = skipString(src, k, c) - 1;
            }
        }
        return -1;
    }

    private static int skipSpaces(String src, int from) {
        int j = from;
        while (j < src.length() && Character.isWhitespace(src.charAt(j))) {
            j++;
        }
        return j;
    }

    private static int skipString(String src, int start, char quote) {
        int j = start + 1;
        while (j < src.length()) {
            char t = src.charAt(j);
            if (t == '\\') {
                j += 2;
                continue;
            }
            if (t == quote) {
                return j + 1;
            }
            if (t == '\n' && quote != '`') {
                return j;
            }
            j++;
        }
        return src.length();
    }

    private static boolean regexAllowed(char last, String lastWord) {
        if (last == 0) {
            return true;
        }
        if (last == 'a') {
            return Set.of("return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw",
                    "instanceof", "yield").contains(lastWord);
        }
        return "(,=:[!&|?{};+-*%<>~^".indexOf(last) >= 0;
    }

    private static int skipRegex(String src, int start) {
        int j = start + 1;
        boolean inClass = false;
        while (j < src.length()) {
            char t = src.charAt(j);
            if (t == '\\') {
                j += 2;
                continue;
            }
            if (t == '\n') {
                return j;
            }
            if (t == '[') {
                inClass = true;
            } else if (t == ']') {
                inClass = false;
            } else if (t == '/' && !inClass) {
                j++;
                while (j < src.length() && Character.isJavaIdentifierPart(src.charAt(j))) {
                    j++;
                }
                return j;
            }
            j++;
        }
        return src.length();
    }

    // ------------------------------------------------------------------ loop heads

    private static void planForIn(String src, ForInLoop loop, List<TextEdit> edits, int[] counter,
                                  List<String> rejections) {
        if (!(loop.getIterator() instanceof VariableDeclaration decl) || decl.isVar()
                || decl.getVariables().size() != 1) {
            return;
        }
        VariableInitializer variable = decl.getVariables().getFirst();
        Set<String> names = targetNames(variable.getTarget());
        if (names.isEmpty() || !closureReferences(loop.getBody(), names)) {
            return;
        }
        String temp = PREFIX + (counter[0]++);
        AstNode target = variable.getTarget();
        String pattern = src.substring(target.getAbsolutePosition(), target.getAbsolutePosition() + target.getLength());
        edits.add(new TextEdit(target.getAbsolutePosition(), target.getLength(), temp));
        insertAtBodyStart(src, loop, "let " + pattern + " = " + temp + ";", edits);
    }

    private static void planFor(String src, ForLoop loop, List<TextEdit> edits, int[] counter,
                                List<String> rejections) {
        if (!(loop.getInitializer() instanceof VariableDeclaration decl) || decl.isVar()) {
            return;
        }
        List<Name> declared = new ArrayList<>();
        for (VariableInitializer variable : decl.getVariables()) {
            if (variable.getTarget() instanceof Name name) {
                declared.add(name);
            } else {
                return; // Destructuring in a C-style head: leave as is.
            }
        }
        Set<String> names = new LinkedHashSet<>();
        declared.forEach(name -> names.add(name.getIdentifier()));
        if (!closureReferences(loop.getBody(), names)) {
            return;
        }
        Set<String> assigned = assignedNames(loop.getBody(), names);
        if (!assigned.isEmpty()) {
            rejections.add("line " + loop.getLineno() + ": the loop body both creates a closure and assigns the loop variable "
                    + String.join(", ", assigned) + ", which this engine cannot run correctly. Use a while loop, or copy the"
                    + " variable into a let inside the body and assign that instead");
            return;
        }
        List<String> bindings = new ArrayList<>();
        java.util.Map<String, String> renamed = new java.util.LinkedHashMap<>();
        for (Name name : declared) {
            String temp = PREFIX + (counter[0]++) + "_" + name.getIdentifier();
            renamed.put(name.getIdentifier(), temp);
            edits.add(new TextEdit(name.getAbsolutePosition(), name.getLength(), temp));
            bindings.add(name.getIdentifier() + " = " + temp);
        }
        for (VariableInitializer variable : decl.getVariables()) {
            renameReferences(variable.getInitializer(), renamed, edits);
        }
        renameReferences(loop.getCondition(), renamed, edits);
        renameReferences(loop.getIncrement(), renamed, edits);
        insertAtBodyStart(src, loop, "let " + String.join(", ", bindings) + ";", edits);
    }

    private static void insertAtBodyStart(String src, Loop loop, String declaration, List<TextEdit> edits) {
        AstNode body = loop.getBody();
        int start = body.getAbsolutePosition();
        if (start < src.length() && src.charAt(start) == '{') {
            edits.add(new TextEdit(start + 1, 0, " " + declaration + " "));
        } else {
            int end = start + body.getLength();
            edits.add(new TextEdit(start, 0, "{ " + declaration + " "));
            edits.add(new TextEdit(end, 0, " }"));
        }
    }

    private static Set<String> targetNames(AstNode target) {
        Set<String> names = new LinkedHashSet<>();
        if (target == null) {
            return names;
        }
        target.visit(node -> {
            if (node instanceof Name name && !isPropertyName(name)) {
                names.add(name.getIdentifier());
            }
            return true;
        });
        return names;
    }

    private static boolean closureReferences(AstNode body, Set<String> names) {
        if (body == null) {
            return false;
        }
        boolean[] found = {false};
        body.visit(node -> {
            if (found[0]) {
                return false;
            }
            if (node instanceof FunctionNode function) {
                function.visit(inner -> {
                    if (inner instanceof Name name && names.contains(name.getIdentifier()) && !isPropertyName(name)) {
                        found[0] = true;
                    }
                    return !found[0];
                });
                return false;
            }
            return true;
        });
        return found[0];
    }

    private static Set<String> assignedNames(AstNode body, Set<String> names) {
        Set<String> assigned = new LinkedHashSet<>();
        body.visit(node -> {
            AstNode target = null;
            if (node instanceof Assignment assignment) {
                target = assignment.getLeft();
            } else if (node instanceof UpdateExpression update) {
                target = update.getOperand();
            }
            if (target instanceof Name name && names.contains(name.getIdentifier())) {
                assigned.add(name.getIdentifier());
            }
            return true;
        });
        return assigned;
    }

    private static void renameReferences(AstNode node, java.util.Map<String, String> renamed, List<TextEdit> edits) {
        if (node == null) {
            return;
        }
        node.visit(inner -> {
            if (inner instanceof FunctionNode) {
                return false;
            }
            if (inner instanceof Name name && renamed.containsKey(name.getIdentifier()) && !isPropertyName(name)) {
                edits.add(new TextEdit(name.getAbsolutePosition(), name.getLength(), renamed.get(name.getIdentifier())));
            }
            return true;
        });
    }

    private static boolean isPropertyName(Name name) {
        AstNode parent = name.getParent();
        if (parent instanceof PropertyGet get && get.getProperty() == name) {
            return true;
        }
        return parent instanceof ObjectProperty property && property.getKey() == name;
    }

    private static String apply(String src, List<TextEdit> edits) {
        List<TextEdit> sorted = new ArrayList<>(edits);
        // Later positions first so earlier offsets stay valid; inserts at the same spot keep order.
        sorted.sort(Comparator.comparingInt(TextEdit::position).reversed());
        StringBuilder out = new StringBuilder(src);
        int lastStart = Integer.MAX_VALUE;
        for (TextEdit edit : sorted) {
            if (edit.position() + edit.length() > lastStart) {
                continue; // Overlapping edit; keep the original text rather than corrupt it.
            }
            out.replace(edit.position(), edit.position() + edit.length(), edit.text());
            if (edit.length() > 0) {
                lastStart = edit.position();
            }
        }
        return out.toString();
    }

    private record TextEdit(int position, int length, String text) {
    }
}
