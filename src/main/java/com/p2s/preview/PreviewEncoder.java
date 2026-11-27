package com.p2s.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packs the block changes of every pending workspace file for the client's ghost preview.
 * Plain data only, so it can be tested without a Minecraft bootstrap: block states arrive as
 * their serialized strings and coordinates are already in world space.
 *
 * <p>A snapshot is built once and served in pages, because a large structure would not fit in
 * one tool bridge reply. Each op is five ints: x, y, z, palette index, file index.
 */
public final class PreviewEncoder {
    /** Ops per page; about 150 KB of JSON, well under the custom payload limit. */
    public static final int PAGE_OPS = 6000;
    static final int MATERIALS = 5;
    static final String AIR = "minecraft:air";

    private PreviewEncoder() {
    }

    public record Op(int x, int y, int z, String state) {
        boolean removal() {
            return AIR.equals(state);
        }
    }

    public record FileDiff(String path, List<Op> ops) {
    }

    public record Snapshot(String version, JsonArray files, List<String> palette, int[] packed) {
        public int total() {
            return packed.length / 5;
        }
    }

    public static Snapshot build(String version, List<FileDiff> diffs) {
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<Integer> packed = new ArrayList<>();
        JsonArray files = new JsonArray();
        for (int f = 0; f < diffs.size(); f++) {
            FileDiff diff = diffs.get(f);
            files.add(summary(diff));
            for (Op op : diff.ops()) {
                int index = paletteIndex.computeIfAbsent(op.state(), key -> paletteIndex.size());
                packed.add(op.x());
                packed.add(op.y());
                packed.add(op.z());
                packed.add(index);
                packed.add(f);
            }
        }
        int[] array = new int[packed.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = packed.get(i);
        }
        return new Snapshot(version, files, List.copyOf(paletteIndex.keySet()), array);
    }

    /** Page starting at op {@code offset}; the first page also carries the file summaries and palette. */
    public static JsonObject page(Snapshot snapshot, int offset) {
        int total = snapshot.total();
        int start = Math.max(0, Math.min(offset, total));
        int end = Math.min(total, start + PAGE_OPS);
        JsonObject page = new JsonObject();
        page.addProperty("version", snapshot.version());
        page.addProperty("offset", start);
        page.addProperty("total", total);
        page.addProperty("next", end < total ? end : -1);
        if (start == 0) {
            page.add("files", snapshot.files().deepCopy());
            JsonArray palette = new JsonArray();
            snapshot.palette().forEach(palette::add);
            page.add("palette", palette);
        }
        JsonArray ops = new JsonArray();
        for (int i = start * 5; i < end * 5; i++) {
            ops.add(snapshot.packed()[i]);
        }
        page.add("ops", ops);
        return page;
    }

    /** What the review page shows without reading TOML: counts, main materials and the changed box. */
    static JsonObject summary(FileDiff diff) {
        JsonObject json = new JsonObject();
        json.addProperty("path", diff.path());
        int placed = 0;
        int removed = 0;
        Map<String, Integer> materials = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Op op : diff.ops()) {
            if (op.removal()) {
                removed++;
            } else {
                placed++;
                materials.merge(blockId(op.state()), 1, Integer::sum);
            }
            minX = Math.min(minX, op.x());
            minY = Math.min(minY, op.y());
            minZ = Math.min(minZ, op.z());
            maxX = Math.max(maxX, op.x());
            maxY = Math.max(maxY, op.y());
            maxZ = Math.max(maxZ, op.z());
        }
        json.addProperty("placed", placed);
        json.addProperty("removed", removed);
        JsonArray top = new JsonArray();
        materials.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MATERIALS)
                .forEach(entry -> {
                    JsonObject material = new JsonObject();
                    material.addProperty("block", entry.getKey());
                    material.addProperty("count", entry.getValue());
                    top.add(material);
                });
        json.add("materials", top);
        if (!diff.ops().isEmpty()) {
            JsonArray min = new JsonArray();
            min.add(minX);
            min.add(minY);
            min.add(minZ);
            JsonArray max = new JsonArray();
            max.add(maxX);
            max.add(maxY);
            max.add(maxZ);
            json.add("min", min);
            json.add("max", max);
        }
        return json;
    }

    /** {@code minecraft:oak_stairs[facing=north]} → {@code minecraft:oak_stairs}. */
    static String blockId(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }
}
