package com.p2s.workbench;

import java.util.ArrayList;
import java.util.List;

/**
 * Line diff for the review page: committed TOML against the staged TOML. Unchanged runs longer
 * than {@code 2 * context} are folded into one {@link Kind#FOLD} row, the way the mockups show
 * “… N 行未改动 …”.
 */
public final class LineDiff {
    public enum Kind { SAME, ADD, REMOVE, FOLD }

    /** {@code oldLine}/{@code newLine} are 1-based, 0 when the side has no line. */
    public record Row(Kind kind, int oldLine, int newLine, String text, int folded) {
    }

    /** Above this many line pairs the LCS table gets too big; fall back to whole-file replace. */
    static final long MAX_CELLS = 4_000_000L;

    private LineDiff() {
    }

    public static List<Row> diff(String before, String after, int context) {
        String[] a = split(before);
        String[] b = split(after);
        List<Row> rows = (long) a.length * b.length > MAX_CELLS ? replaceAll(a, b) : lcs(a, b);
        return fold(rows, context);
    }

    public static int changedLines(List<Row> rows) {
        int count = 0;
        for (Row row : rows) {
            if (row.kind() == Kind.ADD || row.kind() == Kind.REMOVE) {
                count++;
            }
        }
        return count;
    }

    private static String[] split(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.replace("\r\n", "\n").split("\n", -1);
    }

    private static List<Row> lcs(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        int[][] len = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                len[i][j] = a[i].equals(b[j]) ? len[i + 1][j + 1] + 1 : Math.max(len[i + 1][j], len[i][j + 1]);
            }
        }
        List<Row> rows = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a[i].equals(b[j])) {
                rows.add(new Row(Kind.SAME, i + 1, j + 1, a[i], 0));
                i++;
                j++;
            } else if (j < m && (i == n || len[i][j + 1] >= len[i + 1][j])) {
                rows.add(new Row(Kind.ADD, 0, j + 1, b[j], 0));
                j++;
            } else {
                rows.add(new Row(Kind.REMOVE, i + 1, 0, a[i], 0));
                i++;
            }
        }
        return rows;
    }

    private static List<Row> replaceAll(String[] a, String[] b) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            rows.add(new Row(Kind.REMOVE, i + 1, 0, a[i], 0));
        }
        for (int j = 0; j < b.length; j++) {
            rows.add(new Row(Kind.ADD, 0, j + 1, b[j], 0));
        }
        return rows;
    }

    private static List<Row> fold(List<Row> rows, int context) {
        List<Row> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            if (rows.get(i).kind() != Kind.SAME) {
                out.add(rows.get(i++));
                continue;
            }
            int start = i;
            while (i < rows.size() && rows.get(i).kind() == Kind.SAME) {
                i++;
            }
            int end = i; // exclusive
            int keepHead = start == 0 ? 0 : context;
            int keepTail = end == rows.size() ? 0 : context;
            if (end - start <= keepHead + keepTail + 1) {
                out.addAll(rows.subList(start, end));
                continue;
            }
            out.addAll(rows.subList(start, start + keepHead));
            int folded = end - start - keepHead - keepTail;
            out.add(new Row(Kind.FOLD, 0, 0, "", folded));
            out.addAll(rows.subList(end - keepTail, end));
        }
        return out;
    }
}
