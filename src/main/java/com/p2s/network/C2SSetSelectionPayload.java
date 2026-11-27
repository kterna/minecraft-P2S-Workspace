package com.p2s.network;

import net.minecraft.core.BlockPos;

public record C2SSetSelectionPayload(int pointIndex, BlockPos pos) implements P2SPacket {
    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeVarInt(pointIndex);
        buf.writeBlockPos(pos);
    }

    public static C2SSetSelectionPayload read(P2SPacketBuf buf) {
        return new C2SSetSelectionPayload(buf.readVarInt(), buf.readBlockPos());
    }
}
