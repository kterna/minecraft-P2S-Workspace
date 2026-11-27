package com.p2s.network;

public record S2CChatResponsePayload(
        String assistantText,
        boolean hasStructure,
        String status,
        String messageKey,
        String messageArgsJson
) implements P2SPacket {
    public S2CChatResponsePayload(String assistantText, boolean hasStructure, String status) {
        this(assistantText, hasStructure, status, "", "");
    }

    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeUtf(assistantText == null ? "" : assistantText, 16384);
        buf.writeBoolean(hasStructure);
        buf.writeUtf(status == null ? "" : status, 32);
        buf.writeUtf(messageKey == null ? "" : messageKey, 256);
        buf.writeUtf(messageArgsJson == null ? "" : messageArgsJson, 4096);
    }

    public static S2CChatResponsePayload read(P2SPacketBuf buf) {
        return new S2CChatResponsePayload(
                buf.readUtf(16384),
                buf.readBoolean(),
                buf.readUtf(32),
                buf.readUtf(256),
                buf.readUtf(4096)
        );
    }
}
