package com.p2s;

import com.p2s.agent.vfs.MemoryFs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PatchSynthesizerTest {
    private static StructureBuilder.VbsScriptV2 script(String toml) {
        return WorkspaceTomlCodec.toScript(WorkspaceTomlCodec.parse(toml));
    }

    private static Map<String, String> ops(PatchSynthesizer.Synthesis synthesis) {
        return synthesis.patch().operations.stream().collect(Collectors.toMap(
                op -> op.part == null ? "<palette>" : op.part, op -> op.op, (a, b) -> a + "+" + b));
    }

    @Test
    void describesEachChangedPartWithExactOldActions() {
        String base = MemoryFs.sampleToml();
        String next = base.replace("minecraft:oak_slab", "minecraft:spruce_slab")
                .replace("name = \"walls\"", "name = \"walls2\"")
                + "\n[palette]\nw = \"minecraft:white_wool\"\n";
        StructureBuilder.VbsScriptV2 committed = script(base);
        StructureBuilder.VbsScriptV2 staged = script(next);
        PatchSynthesizer.Synthesis synthesis = PatchSynthesizer.synthesize(committed, staged, "rev-1", "test");

        assertTrue(synthesis.changed());
        assertEquals(Map.of("walls", "delete_part", "walls2", "insert_part", "roof", "replace_part",
                "<palette>", "update_palette"), ops(synthesis));
        // replace/delete carry the exact committed actions, as the validator requires for old_actions.
        PatchModels.PatchOperation roof = synthesis.patch().operations.stream()
                .filter(op -> "roof".equals(op.part)).findFirst().orElseThrow();
        assertEquals(1, roof.oldActions.size());
        assertEquals("minecraft:oak_slab", roof.oldActions.getFirst().block);
        assertEquals("minecraft:spruce_slab", roof.newActions.getFirst().block);
        assertEquals("rev-1", synthesis.patch().baseRevision);
        // PatchValidator and the block diff resolve block states, which needs a bootstrapped game;
        // they are covered by the in-game checks listed in the migration notes.
    }

    @Test
    void identicalScriptsAreUnchanged() {
        PatchSynthesizer.Synthesis synthesis = PatchSynthesizer.synthesize(script(MemoryFs.sampleToml()),
                script(MemoryFs.sampleToml()), "rev-1", "");
        assertFalse(synthesis.changed());
        assertTrue(synthesis.patch().operations.isEmpty());
    }

    @Test
    void emptyPartsNeverProduceInvalidOperations() {
        String withEmpty = MemoryFs.sampleToml() + "\n[[part]]\nname = \"empty\"\npriority = 3\n";
        PatchSynthesizer.Synthesis added = PatchSynthesizer.synthesize(script(MemoryFs.sampleToml()), script(withEmpty), "r", "");
        assertTrue(added.changed());
        assertTrue(added.patch().operations.isEmpty(), "an empty part has nothing for insert_part to carry");
        List<String> filled = PatchSynthesizer.synthesize(script(withEmpty), script(withEmpty.replace(
                "name = \"empty\"\npriority = 3\n", "name = \"empty\"\npriority = 3\n\n[[part.action]]\ntype = \"points\"\n"
                        + "block = \"minecraft:torch\"\nat = [[1, 1, 1]]\n")), "r", "").patch().operations.stream()
                .map(op -> op.op).toList();
        assertEquals(List.of("insert_actions"), filled);
    }
}
