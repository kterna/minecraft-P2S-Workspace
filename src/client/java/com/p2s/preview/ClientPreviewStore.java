package com.p2s.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.ClientSessionState;
import com.p2s.ClientToolBridge;
import com.p2s.P2SMod;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Client copy of the pending block changes, fetched page by page through the {@code preview_blocks}
 * bridge tool whenever the set of pending files or their revisions changes. All state is read and
 * replaced on the Minecraft client thread.
 */
public final class ClientPreviewStore {
    /** Upper bound on ghosts kept in memory; larger previews are truncated with a log line. */
    static final int MAX_GHOSTS = 200_000;

    public record Ghost(BlockPos pos, BlockState state, boolean removal) {
    }

    public record FilePreview(String path, List<Ghost> ghosts, JsonObject summary, AABB bounds) {
    }

    private static List<FilePreview> files = List.of();
    private static String fingerprint = "";
    private static long generation;
    private static boolean hidden;

    private ClientPreviewStore() {
    }

    public static List<FilePreview> files() {
        return files;
    }

    public static FilePreview file(String path) {
        for (FilePreview file : files) {
            if (file.path().equals(path)) {
                return file;
            }
        }
        return null;
    }

    /** “仅看已应用”: hides every ghost without dropping the data. */
    public static boolean hidden() {
        return hidden;
    }

    public static void setHidden(boolean value) {
        hidden = value;
    }

    /** Union of all pending changes, or null when nothing is pending. */
    public static AABB bounds() {
        AABB union = null;
        for (FilePreview file : files) {
            if (file.bounds() != null) {
                union = union == null ? file.bounds() : union.minmax(file.bounds());
            }
        }
        return union;
    }

    /** Called every client tick; starts a fetch when the pending set changed. */
    public static void tick() {
        String next = currentFingerprint();
        if (next.equals(fingerprint)) {
            return;
        }
        fingerprint = next;
        long request = ++generation;
        if (next.isEmpty()) {
            files = List.of();
            return;
        }
        fetch(request, 0, new Page());
    }

    public static void clear() {
        generation++;
        fingerprint = "";
        files = List.of();
        hidden = false;
    }

    static String currentFingerprint() {
        StringBuilder key = new StringBuilder();
        for (ClientSessionState.WorkspaceFileInfo file : ClientSessionState.getWorkspaceFiles()) {
            if (file.hasPendingPatch()) {
                key.append('|').append(file.path()).append('@').append(file.pendingRevision());
            }
        }
        return key.isEmpty() ? "" : ClientSessionState.getProjectId() + key;
    }

    /** Pages accumulated for one request. */
    private static final class Page {
        String version;
        JsonArray summaries = new JsonArray();
        List<String> palette = List.of();
        final List<int[]> ops = new ArrayList<>();
        int count;
    }

    private static void fetch(long request, int offset, Page acc) {
        JsonObject args = new JsonObject();
        if (offset > 0) {
            args.addProperty("offset", offset);
        }
        CompletableFuture<JsonObject> reply = ClientToolBridge.call("preview_blocks", args, () -> request == generation);
        reply.whenComplete((payload, error) -> Minecraft.getInstance().execute(() -> {
            if (request != generation) {
                return;
            }
            if (error != null || payload == null || !payload.has("page")) {
                if (payload != null && "changed".equals(string(payload, "error_code"))) {
                    fingerprint = ""; // Pending files moved on mid-fetch: start again next tick.
                } else {
                    P2SMod.LOGGER.warn("Preview fetch failed: {}", error != null ? error.getMessage()
                            : payload == null ? "empty reply" : string(payload, "error"));
                }
                return;
            }
            JsonObject page = payload.getAsJsonObject("page");
            if (offset == 0) {
                acc.version = string(page, "version");
                acc.summaries = page.has("files") ? page.getAsJsonArray("files") : new JsonArray();
                List<String> palette = new ArrayList<>();
                if (page.has("palette")) {
                    page.getAsJsonArray("palette").forEach(entry -> palette.add(entry.getAsString()));
                }
                acc.palette = palette;
            } else if (!acc.version.equals(string(page, "version"))) {
                fingerprint = "";
                return;
            }
            JsonArray ops = page.getAsJsonArray("ops");
            int[] packed = new int[ops.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = ops.get(i).getAsInt();
            }
            acc.ops.add(packed);
            acc.count += packed.length / 5;
            int next = page.has("next") ? page.get("next").getAsInt() : -1;
            if (next > 0 && acc.count < MAX_GHOSTS) {
                fetch(request, next, acc);
            } else {
                if (next > 0) {
                    P2SMod.LOGGER.warn("Preview truncated at {} blocks", acc.count);
                }
                files = decode(acc);
            }
        }));
    }

    private static List<FilePreview> decode(Page acc) {
        List<BlockState> states = new ArrayList<>(acc.palette.size());
        for (String state : acc.palette) {
            states.add(parseState(state));
        }
        Map<Integer, List<Ghost>> byFile = new HashMap<>();
        for (int[] packed : acc.ops) {
            for (int i = 0; i + 4 < packed.length; i += 5) {
                BlockState state = states.get(packed[i + 3]);
                byFile.computeIfAbsent(packed[i + 4], key -> new ArrayList<>())
                        .add(new Ghost(new BlockPos(packed[i], packed[i + 1], packed[i + 2]), state, state.isAir()));
            }
        }
        List<FilePreview> out = new ArrayList<>();
        for (int f = 0; f < acc.summaries.size(); f++) {
            JsonObject summary = acc.summaries.get(f).getAsJsonObject();
            out.add(new FilePreview(string(summary, "path"), List.copyOf(byFile.getOrDefault(f, List.of())),
                    summary, bounds(summary)));
        }
        return List.copyOf(out);
    }

    private static BlockState parseState(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
        } catch (Exception ex) {
            // A block from a mod this client lacks: still show where it goes.
            return Blocks.MAGENTA_STAINED_GLASS.defaultBlockState();
        }
    }

    private static AABB bounds(JsonObject summary) {
        if (!summary.has("min") || !summary.has("max")) {
            return null;
        }
        JsonArray min = summary.getAsJsonArray("min");
        JsonArray max = summary.getAsJsonArray("max");
        return new AABB(min.get(0).getAsInt(), min.get(1).getAsInt(), min.get(2).getAsInt(),
                max.get(0).getAsInt() + 1, max.get(1).getAsInt() + 1, max.get(2).getAsInt() + 1);
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
