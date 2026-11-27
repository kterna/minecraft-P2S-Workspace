package com.p2s.network;

import net.minecraft.core.BlockPos;

public record S2CSelectionSyncPayload(boolean hasPos1, BlockPos pos1, boolean hasPos2, BlockPos pos2)
        implements P2SPacket {
    @Override
    public void write(P2SPacketBuf buf) {
        buf.writeBoolean(hasPos1);
        if (hasPos1) {
            buf.writeBlockPos(pos1);
        }
        buf.writeBoolean(hasPos2);
        if (hasPos2) {
            buf.writeBlockPos(pos2);
        }
    }

    public static S2CSelectionSyncPayload read(P2SPacketBuf buf) {
        boolean has1 = buf.readBoolean();
        BlockPos p1 = has1 ? buf.readBlockPos() : null;
        boolean has2 = buf.readBoolean();
        BlockPos p2 = has2 ? buf.readBlockPos() : null;
        return new S2CSelectionSyncPayload(has1, p1, has2, p2);
    }
}
