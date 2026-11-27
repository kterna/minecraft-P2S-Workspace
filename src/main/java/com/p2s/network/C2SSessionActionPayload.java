package com.p2s.network;

public record C2SSessionActionPayload(String action, String payload) implements P2SPacket {
    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeUtf(action == null ? "" : action, 32);
        buf.writeUtf(payload == null ? "" : payload, 65536);
    }

    public static C2SSessionActionPayload read(P2SPacketBuf buf) {
        return new C2SSessionActionPayload(buf.readUtf(32), buf.readUtf(65536));
    }
}
