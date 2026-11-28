package com.p2s.compat;

import com.p2s.network.P2SNetworkPayloads;
import com.p2s.network.P2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.function.BiConsumer;

/** Client networking compat layer for MC 1.21.11+ CustomPacketPayload + Identifier. */
public final class P2SClientNetworkCompat {
    private P2SClientNetworkCompat() {
    }

    public static <T extends P2SPacket> void registerReceiver(Class<T> type, BiConsumer<T, Minecraft> handler) {
        var entry = P2SNetworkPayloads.entryFor(type);
        ClientPlayNetworking.registerGlobalReceiver(entry.type(),
                (wrapper, context) -> handler.accept(wrapper.packet(), context.client()));
    }

    public static boolean canSend(Class<? extends P2SPacket> type) {
        return ClientPlayNetworking.canSend(P2SNetworkPayloads.entryFor(type).type());
    }

    public static void send(P2SPacket packet) {
        if (packet != null) {
            ClientPlayNetworking.send(P2SNetworkPayloads.wrap(packet));
        }
    }
}
