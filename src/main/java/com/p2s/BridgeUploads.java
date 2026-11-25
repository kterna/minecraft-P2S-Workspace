package com.p2s;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reassembles tool-bridge arguments larger than one serverbound packet (about 32 KB). The
 * client sends {@link #CHUNK_TOOL} requests {@code {upload_id, index, total, data}} and then the
 * real call with arguments {@code {"__p2s_upload": id}}.
 */
final class BridgeUploads {
    static final String CHUNK_TOOL = "__p2s_upload_chunk";
    static final String REFERENCE_KEY = "__p2s_upload";
    private static final int MAX_TOTAL_CHARS = 1_048_576;
    private static final int MAX_UPLOADS_PER_PLAYER = 4;
    private static final long EXPIRY_MS = 60_000;
    private static final Map<UUID, Map<String, Upload>> UPLOADS = new ConcurrentHashMap<>();

    private BridgeUploads() {
    }

    private static final class Upload {
        final String[] parts;
        final long createdAt = System.currentTimeMillis();
        int received;
        int chars;

        Upload(int total) {
            parts = new String[total];
        }
    }

    static void accept(UUID player, String chunkJson) {
        JsonObject chunk;
        try {
            chunk = JsonParser.parseString(chunkJson == null ? "" : chunkJson).getAsJsonObject();
        } catch (RuntimeException ex) {
            return;
        }
        String id = chunk.has("upload_id") ? chunk.get("upload_id").getAsString() : "";
        int index = chunk.has("index") ? chunk.get("index").getAsInt() : -1;
        int total = chunk.has("total") ? chunk.get("total").getAsInt() : 0;
        String data = chunk.has("data") ? chunk.get("data").getAsString() : "";
        if (id.isBlank() || total <= 0 || total > 256 || index < 0 || index >= total) {
            return;
        }
        Map<String, Upload> uploads = UPLOADS.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        purge(uploads);
        if (!uploads.containsKey(id) && uploads.size() >= MAX_UPLOADS_PER_PLAYER) {
            return;
        }
        Upload upload = uploads.computeIfAbsent(id, ignored -> new Upload(total));
        synchronized (upload) {
            if (upload.parts.length != total || upload.parts[index] != null) {
                return;
            }
            if (upload.chars + data.length() > MAX_TOTAL_CHARS) {
                uploads.remove(id);
                return;
            }
            upload.parts[index] = data;
            upload.received++;
            upload.chars += data.length();
        }
    }

    /** The upload id when the arguments are only an upload reference. */
    static String reference(JsonElement arguments) {
        if (arguments == null || !arguments.isJsonObject()) {
            return null;
        }
        JsonObject object = arguments.getAsJsonObject();
        if (object.size() != 1 || !object.has(REFERENCE_KEY)) {
            return null;
        }
        return object.get(REFERENCE_KEY).getAsString();
    }

    /** The assembled text, or null when parts are missing or the upload expired. Single use. */
    static String take(UUID player, String id) {
        Map<String, Upload> uploads = UPLOADS.get(player);
        if (uploads == null) {
            return null;
        }
        Upload upload = uploads.remove(id);
        if (upload == null) {
            return null;
        }
        synchronized (upload) {
            if (upload.received != upload.parts.length) {
                return null;
            }
            StringBuilder text = new StringBuilder(upload.chars);
            for (String part : upload.parts) {
                text.append(part);
            }
            return text.toString();
        }
    }

    private static void purge(Map<String, Upload> uploads) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Upload>> iterator = uploads.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().createdAt > EXPIRY_MS) {
                iterator.remove();
            }
        }
    }
}
