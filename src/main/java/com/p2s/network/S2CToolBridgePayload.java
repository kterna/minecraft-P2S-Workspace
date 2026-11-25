package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CToolBridgePayload(String requestId, boolean ok, String responseJson, String error) implements CustomPacketPayload {
    /** Well under the 1 MiB clientbound custom payload limit even for 3-byte UTF-8 text. */
    public static final int MAX_RESPONSE_CHARS = 262_144;
    public static final Type<S2CToolBridgePayload> TYPE = new Type<>(P2SNetworkConstants.S2C_TOOL_BRIDGE_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CToolBridgePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.requestId == null ? "" : payload.requestId, 128);
                buf.writeBoolean(payload.ok);
                buf.writeUtf(payload.responseJson == null ? "" : payload.responseJson, MAX_RESPONSE_CHARS);
                buf.writeUtf(payload.error == null ? "" : payload.error, 4096);
            },
            buf -> new S2CToolBridgePayload(
                    buf.readUtf(128),
                    buf.readBoolean(),
                    buf.readUtf(MAX_RESPONSE_CHARS),
                    buf.readUtf(4096)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
