package com.p2s.agent.vfs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VfsTest {
    @Test
    void pathsRejectTraversalAndUnknownRoots() throws Exception {
        assertEquals("workspace/a.toml", VfsPaths.normalize("/workspace//./a.toml", false));
        assertThrows(VfsException.class, () -> VfsPaths.normalize("workspace/../etc/passwd", false));
        assertThrows(VfsException.class, () -> VfsPaths.normalize("config/p2s_client.json", false));
        assertEquals("", VfsPaths.normalize("", true));
        assertTrue(VfsPaths.globMatches("workspace/**/*.toml", "workspace/a/b/c.toml"));
        assertTrue(VfsPaths.globMatches("workspace/**/*.toml", "workspace/c.toml"));
        assertFalse(VfsPaths.globMatches("workspace/*.toml", "workspace/a/c.toml"));
    }

    @Test
    void overlayKeepsWritesUntilCommitAndSeesThemMeanwhile() throws Exception {
        MemoryFs base = new MemoryFs().put("workspace/a.toml", "A").put("workspace/b.toml", "B");
        OverlayFs overlay = new OverlayFs(base, 1_000);
        overlay.write("workspace/a.toml", "A2");
        overlay.write("workspace/new/c.toml", "C");
        overlay.delete("workspace/b.toml");
        overlay.move("workspace/a.toml", "workspace/z.toml");

        assertEquals("A2", overlay.read("workspace/z.toml"));
        assertFalse(overlay.exists("workspace/b.toml"));
        assertTrue(overlay.isDirectory("workspace/new"));
        assertEquals(List.of("workspace/new", "workspace/z.toml"),
                overlay.list("workspace").stream().map(VirtualFs.Entry::path).sorted().toList());
        assertTrue(base.log.isEmpty(), "nothing reaches the base before commit");

        assertTrue(overlay.commit().stream().allMatch(OverlayFs.CommitResult::ok));
        assertEquals("A2", base.files.get("workspace/z.toml"));
        assertEquals("C", base.files.get("workspace/new/c.toml"));
        assertFalse(base.files.containsKey("workspace/b.toml"));
        assertFalse(base.files.containsKey("workspace/a.toml"));
    }

    @Test
    void pristineMoveIsAServerMoveAndReadableBeforeCommit() throws Exception {
        MemoryFs base = new MemoryFs().put("workspace/a.toml", "A");
        OverlayFs overlay = new OverlayFs(base, 1_000);
        overlay.move("workspace/a.toml", "workspace/b.toml");
        assertEquals("A", overlay.read("workspace/b.toml"));
        overlay.commit();
        assertEquals(List.of("move workspace/a.toml workspace/b.toml"), base.log);
    }

    @Test
    void overlayEnforcesWritableRootAndSizeLimit() {
        OverlayFs overlay = new OverlayFs(new MemoryFs(), 10);
        assertThrows(VfsException.class, () -> overlay.write("skills/x/SKILL.md", "x"));
        assertThrows(VfsException.class, () -> overlay.write("workspace/a.toml", "01234567890"));
    }

    @Test
    void editsMustMatchExactlyOnce() throws Exception {
        String text = "a = 1\nb = 2\nb = 2\n";
        assertEquals("a = 9\nb = 2\nb = 2\n", TextEdits.apply(text, List.of(new TextEdits.Edit("a = 1", "a = 9"))));
        VfsException twice = assertThrows(VfsException.class,
                () -> TextEdits.apply(text, List.of(new TextEdits.Edit("b = 2", "b = 3"))));
        assertTrue(twice.getMessage().contains("2 times"));
        VfsException missing = assertThrows(VfsException.class,
                () -> TextEdits.apply(text, List.of(new TextEdits.Edit("a = 1", "x"), new TextEdits.Edit("c", "d"))));
        assertTrue(missing.getMessage().startsWith("Edit 2"));
        assertEquals("x = 1\n", TextEdits.apply("x = 0\r\n", List.of(new TextEdits.Edit("x = 0\n", "x = 1\n"))));
    }

    @Test
    void workspaceDocumentsRoundTripAndSetPart() throws Exception {
        JsonObject document = WorkspaceDocs.parse(MemoryFs.sampleToml());
        assertEquals(List.of("walls", "roof"), WorkspaceDocs.partNames(document));
        assertFalse(document.toString().contains("\"facing\":\"\""), "empty optional fields are dropped");
        String printed = WorkspaceDocs.print(document);
        assertEquals(WorkspaceDocs.parse(printed), document);

        JsonArray actions = JsonParser.parseString("[{\"type\":\"points\",\"block\":\"minecraft:torch\",\"at\":[[1,1,1]]}]").getAsJsonArray();
        JsonObject appended = WorkspaceDocs.parse(WorkspaceDocs.setPart(printed, "walls", actions, true, null));
        assertEquals(2, appended.getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonArray("actions").size());
        JsonObject created = WorkspaceDocs.parse(WorkspaceDocs.setPart(printed, "lights", actions, false, 5));
        assertEquals(List.of("walls", "roof", "lights"), WorkspaceDocs.partNames(created));
        assertThrows(VfsException.class, () -> WorkspaceDocs.parse("[[part]\nname = 1"));
    }
}
