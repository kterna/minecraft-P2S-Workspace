package com.p2s.network;

public record C2SToolBridgePayload(String requestId, String toolName, String argumentsJson) implements P2SPacket {
    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeUtf(requestId == null ? "" : requestId, 128);
        buf.writeUtf(toolName == null ? "" : toolName, 128);
        buf.writeUtf(argumentsJson == null ? "" : argumentsJson, 32767);
    }

    public static C2SToolBridgePayload read(P2SPacketBuf buf) {
        return new C2SToolBridgePayload(
                buf.readUtf(128),
                buf.readUtf(128),
                buf.readUtf(32767)
        );
    }
}
