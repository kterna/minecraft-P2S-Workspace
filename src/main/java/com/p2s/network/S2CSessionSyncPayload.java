package com.p2s.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public record S2CSessionSyncPayload(
        boolean hasProject,
        boolean sessionActive,
        String sessionId,
        String projectId,
        String projectName,
        String projectDescription,
        int originX,
        int originY,
        int originZ,
        boolean hasSize,
        int sizeX,
        int sizeY,
        int sizeZ,
        String selectedWorkspacePath,
        int partCount,
        int totalBlocks,
        String partsSummary,
        String structureSummary,
        String runtimeState,
        String revision,
        boolean hasPendingPatch,
        String pendingPath,
        String pendingSummary,
        String pendingRisk,
        int pendingChangedBlocks,
        String checkpointsJson,
        String currentWorkspaceToml,
        String workspaceFilesJson
) implements P2SPacket {
    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeBoolean(hasProject);
        buf.writeBoolean(sessionActive);
        buf.writeUtf(sessionId == null ? "" : sessionId, 64);
        buf.writeUtf(projectId == null ? "" : projectId, 128);
        buf.writeUtf(projectName == null ? "" : projectName, 256);
        buf.writeUtf(fitDisplayText(projectDescription, 1024), 1024);
        buf.writeInt(originX);
        buf.writeInt(originY);
        buf.writeInt(originZ);
        buf.writeBoolean(hasSize);
        buf.writeInt(sizeX);
        buf.writeInt(sizeY);
        buf.writeInt(sizeZ);
        buf.writeUtf(selectedWorkspacePath == null ? "" : selectedWorkspacePath, 256);
        buf.writeVarInt(partCount);
        buf.writeVarInt(totalBlocks);
        buf.writeUtf(fitDisplayText(partsSummary, 1024), 1024);
        buf.writeUtf(fitDisplayText(structureSummary, 8192), 8192);
        buf.writeUtf(runtimeState == null ? "" : runtimeState, 64);
        buf.writeUtf(revision == null ? "" : revision, 128);
        buf.writeBoolean(hasPendingPatch);
        buf.writeUtf(pendingPath == null ? "" : pendingPath, 256);
        buf.writeUtf(fitDisplayText(pendingSummary, 2048), 2048);
        buf.writeUtf(pendingRisk == null ? "" : pendingRisk, 32);
        buf.writeVarInt(pendingChangedBlocks);
        buf.writeUtf(encodeLargeJson(checkpointsJson, 16384, "[]"), 16384);
        buf.writeUtf(encodeLargeJson(currentWorkspaceToml, 65536, ""), 65536);
        buf.writeUtf(encodeLargeJson(workspaceFilesJson, 32767, "[]"), 32767);
    }

    public static S2CSessionSyncPayload read(P2SPacketBuf buf) {
        return new S2CSessionSyncPayload(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(64),
                buf.readUtf(128),
                buf.readUtf(256),
                buf.readUtf(1024),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(256),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf(1024),
                buf.readUtf(8192),
                buf.readUtf(64),
                buf.readUtf(128),
                buf.readBoolean(),
                buf.readUtf(256),
                buf.readUtf(2048),
                buf.readUtf(32),
                buf.readVarInt(),
                decodeLargeJson(buf.readUtf(16384), "[]"),
                decodeLargeJson(buf.readUtf(65536), ""),
                decodeLargeJson(buf.readUtf(32767), "[]")
        );
    }

    private static final String COMPRESSED_PREFIX = "@p2s-gzip@";

    private static String fitDisplayText(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, Math.max(0, maxLength));
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private static String encodeLargeJson(String value, int maxLength, String fallback) {
        String normalized = value == null ? fallback : value;
        if (normalized == null || normalized.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        String compressed = compressToTaggedBase64(normalized);
        if (compressed != null && compressed.length() <= maxLength) {
            return compressed;
        }
        return fallback == null ? "" : fallback;
    }

    private static String decodeLargeJson(String value, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        if (!value.startsWith(COMPRESSED_PREFIX)) {
            return value;
        }
        String decoded = decompressTaggedBase64(value);
        if (decoded == null) {
            return fallback == null ? "" : fallback;
        }
        return decoded;
    }

    private static String compressToTaggedBase64(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(value.getBytes(StandardCharsets.UTF_8));
            }
            return COMPRESSED_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String decompressTaggedBase64(String value) {
        if (value == null || !value.startsWith(COMPRESSED_PREFIX)) {
            return null;
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(value.substring(COMPRESSED_PREFIX.length()));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
