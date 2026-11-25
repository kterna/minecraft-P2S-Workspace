package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The v2 {@code inspect_world} tool: reads the real blocks in a region of the current
 * project. Small regions return per-layer grids; large ones only block counts. Unloaded
 * chunks are reported, never loaded.
 */
final class WorldInspector {
    static final int MAX_GRID_VOLUME = 32_768;
    static final int MAX_STATS_VOLUME = 1_000_000;
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private WorldInspector() {
    }

    static JsonObject inspect(ServerPlayer player, ProjectPersistence.ProjectRecord project, JsonObject args) {
        if (project == null) {
            return error("No project is open");
        }
        int[] from;
        int[] to;
        try {
            if (args.has("path")) {
                ProjectPersistence.WorkspaceFileRecord workspace = project.workspaceFiles.get(
                        ProjectPersistence.normalizeWorkspacePath(args.get("path").getAsString()));
                if (workspace == null || workspace.origin == null || workspace.size == null) {
                    return error("Workspace file not found or has no origin/size: " + args.get("path").getAsString());
                }
                from = new int[]{workspace.origin.x, workspace.origin.y, workspace.origin.z};
                to = new int[]{workspace.origin.x + workspace.size.x - 1, workspace.origin.y + workspace.size.y - 1,
                        workspace.origin.z + workspace.size.z - 1};
            } else {
                from = vec(args, "from");
                to = vec(args, "to");
            }
        } catch (RuntimeException ex) {
            return error("Give either path or from/to world coordinates [x, y, z]: " + ex.getMessage());
        }
        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);
        if (project.boundsOrigin != null && project.boundsSize != null) {
            int bx = project.boundsOrigin.x;
            int by = project.boundsOrigin.y;
            int bz = project.boundsOrigin.z;
            if (minX < bx || minY < by || minZ < bz || maxX >= bx + project.boundsSize.x
                    || maxY >= by + project.boundsSize.y || maxZ >= bz + project.boundsSize.z) {
                return error("Region is outside the project bounds " + bx + "," + by + "," + bz + " size "
                        + project.boundsSize.x + "x" + project.boundsSize.y + "x" + project.boundsSize.z);
            }
        }
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_STATS_VOLUME) {
            return error("Region has " + volume + " blocks; the limit is " + MAX_STATS_VOLUME + ". Inspect a smaller box");
        }
        boolean grid = volume <= MAX_GRID_VOLUME && !(args.has("stats_only") && args.get("stats_only").getAsBoolean());
        ServerLevel level = player.serverLevel();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Character> legend = new LinkedHashMap<>();
        List<JsonObject> layers = new ArrayList<>();
        int unloaded = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            List<String> rows = new ArrayList<>();
            boolean layerHasBlocks = false;
            for (int z = minZ; z <= maxZ; z++) {
                StringBuilder row = grid ? new StringBuilder() : null;
                for (int x = minX; x <= maxX; x++) {
                    pos.set(x, y, z);
                    if (!level.isLoaded(pos)) {
                        unloaded++;
                        if (row != null) {
                            row.append('?');
                        }
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        if (row != null) {
                            row.append('.');
                        }
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    counts.merge(id, 1, Integer::sum);
                    layerHasBlocks = true;
                    if (row != null) {
                        Character symbol = legend.get(id);
                        if (symbol == null) {
                            symbol = legend.size() < SYMBOLS.length() ? SYMBOLS.charAt(legend.size()) : '*';
                            if (symbol != '*') {
                                legend.put(id, symbol);
                            }
                        }
                        row.append(symbol);
                    }
                }
                if (row != null) {
                    rows.add(row.toString());
                }
            }
            if (grid && layerHasBlocks) {
                JsonObject layer = new JsonObject();
                layer.addProperty("y", y);
                JsonArray rowArray = new JsonArray();
                rows.forEach(rowArray::add);
                layer.add("rows", rowArray);
                layers.add(layer);
            }
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", "inspect_world");
        payload.add("from", arr(minX, minY, minZ));
        payload.add("to", arr(maxX, maxY, maxZ));
        payload.addProperty("volume", volume);
        int solid = counts.values().stream().mapToInt(Integer::intValue).sum();
        payload.addProperty("non_air", solid);
        if (unloaded > 0) {
            payload.addProperty("unloaded", unloaded);
        }
        JsonObject countJson = new JsonObject();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(60)
                .forEach(entry -> countJson.addProperty(entry.getKey(), entry.getValue()));
        payload.add("block_counts", countJson);
        if (grid) {
            JsonObject legendJson = new JsonObject();
            legend.forEach((id, symbol) -> legendJson.addProperty(String.valueOf(symbol), id));
            payload.add("legend", legendJson);
            payload.addProperty("grid_format", "rows run along +x starting at from.x; one row per z from from.z; "
                    + "'.' air, '?' unloaded, '*' more than 62 block types; layers without blocks are omitted");
            JsonArray layerArray = new JsonArray();
            layers.forEach(layerArray::add);
            payload.add("layers", layerArray);
        } else {
            payload.addProperty("note", "Region larger than " + MAX_GRID_VOLUME + " blocks: counts only");
        }
        return payload;
    }

    private static int[] vec(JsonObject args, String key) {
        JsonArray a = args.getAsJsonArray(key);
        if (a == null || a.size() != 3) {
            throw new IllegalArgumentException(key + " must be [x, y, z]");
        }
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    private static JsonArray arr(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    private static JsonObject error(String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", "inspect_world");
        payload.addProperty("error", message);
        return payload;
    }
}
