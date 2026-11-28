package com.p2s.compat;

import com.p2s.network.P2SNetworkPayloads;
import com.p2s.network.P2SPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Server networking compat layer for MC 1.21.11+ CustomPacketPayload + Identifier. */
public final class P2SServerNetworkCompat {
    private P2SServerNetworkCompat() {
    }

    public static void registerDisconnect(Consumer<ServerPlayer> onDisconnect) {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect.accept(handler.player));
    }

    public static <T extends P2SPacket> void registerReceiver(Class<T> type, BiConsumer<T, ServerPlayer> handler) {
        var entry = P2SNetworkPayloads.entryFor(type);
        ServerPlayNetworking.registerGlobalReceiver(entry.type(),
                (wrapper, context) -> handler.accept(wrapper.packet(), context.player()));
    }

    public static void sendToClient(ServerPlayer player, P2SPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        var wrapped = P2SNetworkPayloads.wrap(packet);
        if (ServerPlayNetworking.canSend(player, wrapped.type())) {
            ServerPlayNetworking.send(player, wrapped);
        }
    }
}
