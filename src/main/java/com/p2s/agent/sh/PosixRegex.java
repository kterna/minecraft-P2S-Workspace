package com.p2s.agent.sh;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Converts POSIX basic/extended regular expressions and sed replacements to java.util.regex. */
public final class PosixRegex {
    private PosixRegex() {
    }

    public static Pattern compile(String pattern, boolean extended, boolean literal, boolean ignoreCase, boolean wholeWord) {
        String body = literal ? Pattern.quote(pattern) : (extended ? fromExtended(pattern) : fromBasic(pattern));
        if (wholeWord) {
            body = "\\b(?:" + body + ")\\b";
        }
        try {
            return Pattern.compile(body, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException ex) {
            throw new ShellError("invalid regular expression '" + pattern + "': " + ex.getDescription());
        }
    }

    /** BRE: \( \) \{ \} \| \+ \? are operators; bare ( ) { } | + ? are literals. */
    static String fromBasic(String re) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < re.length(); i++) {
            char c = re.charAt(i);
            if (c == '[') {
                i = copyBracket(re, i, out);
                continue;
            }
            if (c == '\\' && i + 1 < re.length()) {
                char next = re.charAt(++i);
                if ("(){}|+?".indexOf(next) >= 0) {
                    out.append(next);
                } else {
                    out.append('\\').append(next);
                }
                continue;
            }
            if ("(){}|+?".indexOf(c) >= 0) {
                out.append('\\').append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    static String fromExtended(String re) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < re.length(); i++) {
            char c = re.charAt(i);
            if (c == '[') {
                i = copyBracket(re, i, out);
                continue;
            }
            if (c == '\\' && i + 1 < re.length()) {
                out.append(c).append(re.charAt(++i));
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Copies a bracket expression, translating POSIX classes like [:digit:]. Returns the last index. */
    private static int copyBracket(String re, int start, StringBuilder out) {
        int i = start + 1;
        StringBuilder body = new StringBuilder("[");
        if (i < re.length() && re.charAt(i) == '^') {
            body.append('^');
            i++;
        }
        if (i < re.length() && re.charAt(i) == ']') {
            body.append("\\]");
            i++;
        }
        while (i < re.length()) {
            char c = re.charAt(i);
            if (c == ']') {
                body.append(']');
                out.append(body);
                return i;
            }
            if (c == '[' && i + 1 < re.length() && re.charAt(i + 1) == ':') {
                int end = re.indexOf(":]", i + 2);
                if (end > 0) {
                    body.append(posixClass(re.substring(i + 2, end)));
                    i = end + 2;
                    continue;
                }
            }
            if (c == '[' || c == '&' || c == '~') {
                body.append('\\');
            }
            if (c == '\\') {
                body.append("\\\\");
                i++;
                continue;
            }
            body.append(c);
            i++;
        }
        // Unterminated: treat the bracket literally.
        out.append("\\[");
        return start;
    }

    private static String posixClass(String name) {
        return switch (name) {
            case "alpha" -> "\\p{Alpha}";
            case "digit" -> "\\p{Digit}";
            case "alnum" -> "\\p{Alnum}";
            case "upper" -> "\\p{Upper}";
            case "lower" -> "\\p{Lower}";
            case "space" -> "\\s";
            case "blank" -> " \\t";
            case "punct" -> "\\p{Punct}";
            case "xdigit" -> "\\p{XDigit}";
            default -> throw new ShellError("unknown character class [:" + name + ":]");
        };
    }

    /** sed replacement: {@code &} is the match, {@code \1}..{@code \9} are groups, {@code \n} a newline. */
    public static String replacement(String rep) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rep.length(); i++) {
            char c = rep.charAt(i);
            if (c == '\\' && i + 1 < rep.length()) {
                char next = rep.charAt(++i);
                if (Character.isDigit(next)) {
                    out.append('$').append(next);
                } else if (next == 'n') {
                    out.append('\n');
                } else if (next == 't') {
                    out.append('\t');
                } else if (next == '\\') {
                    out.append("\\\\");
                } else if (next == '$') {
                    out.append("\\$");
                } else {
                    out.append(next);
                }
            } else if (c == '&') {
                out.append("$0");
            } else if (c == '$') {
                out.append("\\$");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
