package com.p2s.preview;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreviewEncoderTest {
    private static PreviewEncoder.Op op(int x, int y, int z, String state) {
        return new PreviewEncoder.Op(x, y, z, state);
    }

    @Test
    void summaryCountsPlacedAndRemovedBlocksAndRanksMaterials() {
        var diff = new PreviewEncoder.FileDiff("roof.toml", List.of(
                op(0, 5, 0, "minecraft:oak_stairs[facing=north]"),
                op(1, 5, 0, "minecraft:oak_stairs[facing=south]"),
                op(2, 6, 3, "minecraft:glass"),
                op(-1, 4, 0, "minecraft:air")));
        JsonObject summary = PreviewEncoder.summary(diff);

        assertEquals(3, summary.get("placed").getAsInt());
        assertEquals(1, summary.get("removed").getAsInt());
        var materials = summary.getAsJsonArray("materials");
        assertEquals("minecraft:oak_stairs", materials.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals(2, materials.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("[-1,4,0]", summary.get("min").toString());
        assertEquals("[2,6,3]", summary.get("max").toString());
    }

    @Test
    void sharedStatesUseOnePaletteEntryAndOpsKeepTheirFile() {
        var snapshot = PreviewEncoder.build("v1", List.of(
                new PreviewEncoder.FileDiff("a.toml", List.of(op(0, 0, 0, "minecraft:stone"))),
                new PreviewEncoder.FileDiff("b.toml", List.of(op(1, 0, 0, "minecraft:stone"), op(2, 0, 0, "minecraft:air")))));

        assertEquals(List.of("minecraft:stone", "minecraft:air"), snapshot.palette());
        assertEquals(3, snapshot.total());
        JsonObject page = PreviewEncoder.page(snapshot, 0);
        assertEquals("[0,0,0,0,0,1,0,0,0,1,2,0,0,1,1]", page.get("ops").toString());
        assertEquals(-1, page.get("next").getAsInt());
        assertEquals(2, page.getAsJsonArray("files").size());
    }

    @Test
    void largePreviewsArePagedAndOnlyTheFirstPageCarriesTheHeader() {
        List<PreviewEncoder.Op> ops = new ArrayList<>();
        for (int i = 0; i < PreviewEncoder.PAGE_OPS + 10; i++) {
            ops.add(op(i, 0, 0, "minecraft:stone"));
        }
        var snapshot = PreviewEncoder.build("v2", List.of(new PreviewEncoder.FileDiff("big.toml", ops)));

        JsonObject first = PreviewEncoder.page(snapshot, 0);
        assertEquals(PreviewEncoder.PAGE_OPS, first.get("next").getAsInt());
        assertEquals(PreviewEncoder.PAGE_OPS * 5, first.getAsJsonArray("ops").size());
        JsonObject second = PreviewEncoder.page(snapshot, PreviewEncoder.PAGE_OPS);
        assertEquals(-1, second.get("next").getAsInt());
        assertEquals(50, second.getAsJsonArray("ops").size());
        assertFalse(second.has("palette"));
        assertFalse(second.has("files"));
        assertEquals("v2", second.get("version").getAsString());
    }

    @Test
    void emptyPreviewHasNoBounds() {
        JsonObject page = PreviewEncoder.page(PreviewEncoder.build("", List.of()), 0);
        assertEquals(0, page.get("total").getAsInt());
        assertEquals(-1, page.get("next").getAsInt());
        assertFalse(PreviewEncoder.summary(new PreviewEncoder.FileDiff("x", List.of())).has("min"));
    }
}
