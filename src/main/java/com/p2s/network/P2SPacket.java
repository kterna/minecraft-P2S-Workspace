package com.p2s.network;

/** Marker for P2S network packets serialized through version-specific transports. */
public interface P2SPacket {
    void write(P2SPacketBuf buf);
}
