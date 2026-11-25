package com.p2s.agent.vfs;

import java.util.List;

/** Exact-text replacement used by the {@code edit} tool, in the style of pi's edit tool. */
public final class TextEdits {
    private TextEdits() {
    }

    public record Edit(String oldText, String newText) {
    }

    /**
     * Applies edits in order. Each {@code oldText} must occur exactly once in the text as it
     * is after the previous edits; otherwise nothing is applied and the error says why.
     */
    public static String apply(String text, List<Edit> edits) throws VfsException {
        if (edits == null || edits.isEmpty()) {
            throw new VfsException("No edits given");
        }
        String current = normalizeNewlines(text);
        int index = 0;
        for (Edit edit : edits) {
            index++;
            String oldText = normalizeNewlines(edit.oldText());
            String newText = normalizeNewlines(edit.newText());
            if (oldText.isEmpty()) {
                throw new VfsException("Edit " + index + ": old_text is empty");
            }
            int first = current.indexOf(oldText);
            if (first < 0) {
                String hint = current.contains(oldText.strip())
                        ? " (it matches after trimming whitespace; copy the exact text including indentation)"
                        : " (read the file again; it may have changed)";
                throw new VfsException("Edit " + index + ": old_text not found" + hint);
            }
            int second = current.indexOf(oldText, first + 1);
            if (second >= 0) {
                throw new VfsException("Edit " + index + ": old_text occurs " + countOccurrences(current, oldText)
                        + " times; include more surrounding lines so it is unique");
            }
            current = current.substring(0, first) + newText + current.substring(first + oldText.length());
        }
        return current;
    }

    static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += Math.max(1, needle.length());
        }
        return count;
    }

    public static String normalizeNewlines(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
