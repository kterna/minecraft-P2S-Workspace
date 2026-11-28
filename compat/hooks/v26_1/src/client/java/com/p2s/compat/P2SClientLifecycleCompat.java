package com.p2s.compat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Client connection, tick, chat message, and block interaction lifecycle hooks for MC 26.1+. */
public final class P2SClientLifecycleCompat {
    private P2SClientLifecycleCompat() {
    }

    public static void registerJoin(Consumer<Minecraft> listener) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> listener.accept(client)));
    }

    public static void registerDisconnect(Consumer<Minecraft> listener) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> listener.accept(client)));
    }

    public static void registerEndClientTick(Consumer<Minecraft> listener) {
        ClientTickEvents.END_CLIENT_TICK.register(listener::accept);
    }

    public static void addChatMessage(Minecraft client, Component message) {
        if (client != null && client.gui != null && message != null) {
            client.gui.getChat().addClientSystemMessage(message);
        }
    }

    public static void registerSelectionCallbacks(
            Predicate<Player> selectionToolHeld,
            Consumer<BlockPos> onLeftClick,
            Consumer<BlockPos> onRightClick
    ) {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!selectionToolHeld.test(player)) {
                return InteractionResult.PASS;
            }
            onLeftClick.accept(pos);
            return InteractionResult.SUCCESS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!selectionToolHeld.test(player)) {
                return InteractionResult.PASS;
            }
            onRightClick.accept(hitResult.getBlockPos());
            return InteractionResult.SUCCESS;
        });
    }
}
