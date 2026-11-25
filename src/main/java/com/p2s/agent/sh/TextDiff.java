package com.p2s.agent.sh;

import java.util.ArrayList;
import java.util.List;

/** Line-based unified diff (LCS), used by {@code sh diff} and the v2 tools. */
public final class TextDiff {
    private static final long MAX_CELLS = 4_000_000L;

    private TextDiff() {
    }

    public static String unified(String oldName, String newName, String oldText, String newText, int context) {
        List<String> a = lines(oldText);
        List<String> b = lines(newText);
        if (a.equals(b)) {
            return "";
        }
        if ((long) a.size() * b.size() > MAX_CELLS) {
            return "--- " + oldName + "\n+++ " + newName + "\n(files differ: " + a.size() + " vs " + b.size()
                    + " lines; too large for a line diff)\n";
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // Edit script: ' ' keep, '-' delete, '+' insert, with old/new line numbers.
        List<char[]> ops = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<int[]> positions = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                ops.add(new char[]{' '});
                texts.add(a.get(i));
                positions.add(new int[]{i, j});
                i++;
                j++;
            } else if (j < b.size() && (i == a.size() || lcs[i][j + 1] > lcs[i + 1][j])) {
                ops.add(new char[]{'+'});
                texts.add(b.get(j));
                positions.add(new int[]{i, j});
                j++;
            } else {
                ops.add(new char[]{'-'});
                texts.add(a.get(i));
                positions.add(new int[]{i, j});
                i++;
            }
        }
        StringBuilder out = new StringBuilder("--- " + oldName + "\n+++ " + newName + "\n");
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k)[0] == ' ') {
                k++;
                continue;
            }
            int start = Math.max(0, k - context);
            int end = k;
            // Extend the hunk while changes are within 2*context lines of each other.
            while (end < ops.size()) {
                if (ops.get(end)[0] != ' ') {
                    end++;
                    continue;
                }
                int run = end;
                while (run < ops.size() && ops.get(run)[0] == ' ') {
                    run++;
                }
                if (run < ops.size() && run - end <= 2 * context) {
                    end = run;
                } else {
                    end = Math.min(ops.size(), end + context);
                    break;
                }
            }
            int oldStart = positions.get(start)[0];
            int newStart = positions.get(start)[1];
            int oldCount = 0;
            int newCount = 0;
            StringBuilder hunk = new StringBuilder();
            for (int h = start; h < end; h++) {
                char op = ops.get(h)[0];
                hunk.append(op).append(texts.get(h)).append('\n');
                if (op != '+') {
                    oldCount++;
                }
                if (op != '-') {
                    newCount++;
                }
            }
            out.append("@@ -").append(oldStart + 1).append(',').append(oldCount)
                    .append(" +").append(newStart + 1).append(',').append(newCount).append(" @@\n").append(hunk);
            k = end;
        }
        return out.toString();
    }

    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String normalized = text.replace("\r\n", "\n");
        int start = 0;
        while (start < normalized.length()) {
            int end = normalized.indexOf('\n', start);
            if (end < 0) {
                out.add(normalized.substring(start));
                break;
            }
            out.add(normalized.substring(start, end));
            start = end + 1;
        }
        return out;
    }
}
