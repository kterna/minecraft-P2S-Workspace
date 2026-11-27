package com.p2s.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineDiffTest {
    @Test
    void changedLinesAreMarkedWithTheirLineNumbers() {
        List<LineDiff.Row> rows = LineDiff.diff("a\nb\nc", "a\nB\nc\nd", 3);
        assertEquals(List.of(LineDiff.Kind.SAME, LineDiff.Kind.ADD, LineDiff.Kind.REMOVE, LineDiff.Kind.SAME,
                LineDiff.Kind.ADD), rows.stream().map(LineDiff.Row::kind).toList());
        assertEquals("B", rows.get(1).text());
        assertEquals(2, rows.get(1).newLine());
        assertEquals(2, rows.get(2).oldLine());
        assertEquals(3, LineDiff.changedLines(rows));
    }

    @Test
    void longUnchangedRunsFoldLeavingContextAroundChanges() {
        StringBuilder before = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            before.append("line").append(i).append('\n');
        }
        String after = before.toString().replace("line10\n", "changed\n");
        List<LineDiff.Row> rows = LineDiff.diff(before.toString(), after, 2);

        assertEquals(LineDiff.Kind.FOLD, rows.getFirst().kind());
        assertEquals(7, rows.getFirst().folded()); // lines 1-7 folded, 8-9 kept before the change
        assertEquals("line8", rows.get(1).text());
        assertTrue(rows.stream().anyMatch(row -> row.kind() == LineDiff.Kind.ADD && row.text().equals("changed")));
        assertEquals(LineDiff.Kind.FOLD, rows.getLast().kind());
    }

    @Test
    void identicalTextIsOneFoldAndNewFileIsAllAdded() {
        List<LineDiff.Row> same = LineDiff.diff("a\nb\nc\nd\ne\nf", "a\nb\nc\nd\ne\nf", 1);
        assertEquals(1, same.size());
        assertEquals(LineDiff.Kind.FOLD, same.getFirst().kind());
        assertEquals(0, LineDiff.changedLines(same));

        List<LineDiff.Row> created = LineDiff.diff("", "x\ny", 2);
        assertEquals(2, LineDiff.changedLines(created));
        assertTrue(created.stream().allMatch(row -> row.kind() == LineDiff.Kind.ADD));
    }
}
