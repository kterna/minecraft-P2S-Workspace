package com.p2s.network;

public record S2CToolBridgePayload(String requestId, boolean ok, String responseJson, String error)
        implements P2SPacket {
    /** Well under the 1 MiB clientbound custom payload limit even for 3-byte UTF-8 text. */
    public static final int MAX_RESPONSE_CHARS = 262_144;

    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeUtf(requestId == null ? "" : requestId, 128);
        buf.writeBoolean(ok);
        buf.writeUtf(responseJson == null ? "" : responseJson, MAX_RESPONSE_CHARS);
        buf.writeUtf(error == null ? "" : error, 4096);
    }

    public static S2CToolBridgePayload read(P2SPacketBuf buf) {
        return new S2CToolBridgePayload(
                buf.readUtf(128),
                buf.readBoolean(),
                buf.readUtf(MAX_RESPONSE_CHARS),
                buf.readUtf(4096)
        );
    }
}
