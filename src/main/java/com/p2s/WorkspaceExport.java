package com.p2s;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Retains one serialization per exporting connection while it reads successive pages. */
final class WorkspaceExport<P> {
    record Revision(String committed, String staged, boolean pending) {
        static Revision from(JsonObject reply) {
            return new Revision(reply.get("committed_revision").getAsString(),
                    reply.get("staged_revision").getAsString(), reply.get("has_pending").getAsBoolean());
        }

        void writeTo(JsonObject payload) {
            payload.addProperty("committed_revision", committed);
            payload.addProperty("staged_revision", staged);
            payload.addProperty("has_pending", pending);
        }
    }

    static Map<String, Revision> revisions(JsonObject files) {
        Map<String, Revision> result = new LinkedHashMap<>();
        files.entrySet().forEach(entry -> result.put(entry.getKey(), Revision.from(entry.getValue().getAsJsonObject())));
        return result;
    }

    record Version(String project, String path, boolean committed, Revision revision) {}
    record Page(String text, int length) {}
    private record Source(Version version, String text) {}

    private final Cache<P, Source> sources = CacheBuilder.newBuilder()
            .weakKeys().maximumWeight(8 * 1024 * 1024)
            .weigher((P player, Source source) -> source.text().length())
            .expireAfterAccess(30, TimeUnit.SECONDS).build();

    Page read(P player, Version version, int offset, Supplier<String> serialize) {
        Source source = offset == 0 ? null : sources.getIfPresent(player);
        if (source == null || !source.version().equals(version)) source = new Source(version, serialize.get());
        String text = source.text();
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("Invalid workspace text offset");
        int end = offset + Math.min(24_000, text.length() - offset);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        if (end < text.length()) sources.put(player, source);
        else release(player);
        return new Page(text.substring(offset, end), text.length());
    }

    void release(P player) {
        sources.invalidate(player);
    }
}
