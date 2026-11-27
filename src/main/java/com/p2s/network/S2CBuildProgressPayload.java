package com.p2s.network;

public record S2CBuildProgressPayload(String phase, String currentPart, int progress, int blocksPlaced)
        implements P2SPacket {
    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeUtf(phase == null ? "" : phase, 32);
        buf.writeUtf(currentPart == null ? "" : currentPart, 64);
        buf.writeVarInt(progress);
        buf.writeVarInt(blocksPlaced);
    }

    public static S2CBuildProgressPayload read(P2SPacketBuf buf) {
        return new S2CBuildProgressPayload(
                buf.readUtf(32),
                buf.readUtf(64),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }
}
