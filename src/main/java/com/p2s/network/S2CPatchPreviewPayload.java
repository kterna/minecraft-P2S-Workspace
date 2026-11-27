package com.p2s.network;

public record S2CPatchPreviewPayload(
        boolean hasPreview,
        String summary,
        String detail,
        int changedBlocks,
        String riskLevel
) implements P2SPacket {
    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeBoolean(hasPreview);
        buf.writeUtf(summary == null ? "" : summary, 2048);
        buf.writeUtf(detail == null ? "" : detail, 32767);
        buf.writeVarInt(changedBlocks);
        buf.writeUtf(riskLevel == null ? "" : riskLevel, 32);
    }

    public static S2CPatchPreviewPayload read(P2SPacketBuf buf) {
        return new S2CPatchPreviewPayload(
                buf.readBoolean(),
                buf.readUtf(2048),
                buf.readUtf(32767),
                buf.readVarInt(),
                buf.readUtf(32)
        );
    }
}
