package com.p2s.network;

import net.minecraft.core.BlockPos;

/** Minimal packet buffer abstraction shared across networking compat tiers. */
public interface P2SPacketBuf {
    void writeBoolean(boolean value);

    boolean readBoolean();

    void writeInt(int value);

    int readInt();

    void writeVarInt(int value);

    int readVarInt();

    void writeUtf(String value, int maxLength);

    String readUtf(int maxLength);

    void writeBlockPos(BlockPos pos);

    BlockPos readBlockPos();
}
