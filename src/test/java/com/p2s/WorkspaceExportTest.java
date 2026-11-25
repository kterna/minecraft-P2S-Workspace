package com.p2s;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceExportTest {
    @Test
    void manifestNoticesReplacementOfPendingRevisionWithoutACommittedChange() {
        JsonObject files = new JsonObject();
        JsonObject entry = new JsonObject();
        new WorkspaceExport.Revision("rev-1", "staged-1", true).writeTo(entry);
        files.add("house.toml", entry);
        var before = WorkspaceExport.revisions(files);
        assertEquals(before, WorkspaceExport.revisions(files.deepCopy()));
        new WorkspaceExport.Revision("rev-1", "staged-2", true).writeTo(entry);
        assertNotEquals(before, WorkspaceExport.revisions(files));
    }

    @Test
    void readsWholeUnicodePagesOnceAndReleasesSnapshotAtEof() {
        String text = "a".repeat(23999) + "🏰" + "b".repeat(23999);
        var pages = new WorkspaceExport<Object>();
        Object player = new Object();
        var version = new WorkspaceExport.Version("p", "house", true, new WorkspaceExport.Revision("r", "r", false));
        var calls = new AtomicInteger();
        java.util.function.Supplier<String> serialize = () -> { calls.incrementAndGet(); return text; };
        var one = pages.read(player, version, 0, serialize);
        assertEquals(23999, one.text().length());
        var two = pages.read(player, version, one.text().length(), serialize);
        var three = pages.read(player, version, one.text().length() + two.text().length(), serialize);
        assertEquals(text, one.text() + two.text() + three.text());
        assertEquals(1, calls.get());
        pages.read(player, version, text.length(), serialize);
        assertEquals(2, calls.get());
    }

    @Test
    void cachesAreIsolatedByConnectionAndWorkspaceRevision() {
        var pages = new WorkspaceExport<Object>();
        String text = "a".repeat(25000);
        var a = new Object();
        var v1 = new WorkspaceExport.Version("p", "house", false, new WorkspaceExport.Revision("r", "1", true));
        var v2 = new WorkspaceExport.Version("p", "house", false, new WorkspaceExport.Revision("r", "2", true));
        pages.read(a, v1, 0, () -> text);
        assertEquals("new", pages.read(new Object(), v1, 24000, () -> "b".repeat(24000) + "new").text());
        assertEquals("updated", pages.read(a, v2, 24000, () -> "c".repeat(24000) + "updated").text());
    }
}
