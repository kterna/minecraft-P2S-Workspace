package com.p2s;

import com.p2s.compat.P2SGameCompat;
import com.p2s.compat.P2SServerNetworkCompat;
import com.p2s.network.C2SSessionActionPayload;
import com.p2s.network.C2SSetSelectionPayload;
import com.p2s.network.C2SToolBridgePayload;
import com.p2s.network.P2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {
    }

    public static void register() {
        P2SServerNetworkCompat.registerDisconnect(SessionManager::clearExportPage);

        P2SServerNetworkCompat.registerReceiver(C2SSetSelectionPayload.class, (payload, player) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] S->C2SSetSelection received -> player={}, pointIndex={}, pos={}",
                        P2SGameCompat.playerName(player), payload.pointIndex(), payload.pos());
            }
            runOnPlayerServer(player, () -> SelectionManager.handleClientSelection(player, payload.pointIndex(), payload.pos()));
        });

        P2SServerNetworkCompat.registerReceiver(C2SSessionActionPayload.class, (payload, player) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] S->C2SSessionAction received -> player={}, action={}, payloadLen={}",
                        P2SGameCompat.playerName(player), payload.action(),
                        payload.payload() == null ? 0 : payload.payload().length());
            }
            runOnPlayerServer(player, () -> SessionManager.handleSessionAction(player, payload.action(), payload.payload()));
        });

        P2SServerNetworkCompat.registerReceiver(C2SToolBridgePayload.class, (payload, player) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] S->C2SToolBridge received -> player={}, requestId={}, tool={}, argsLen={}",
                        P2SGameCompat.playerName(player), payload.requestId(), payload.toolName(),
                        payload.argumentsJson() == null ? 0 : payload.argumentsJson().length());
            }
            runOnPlayerServer(player, () -> SessionManager.handleToolBridgeRequest(
                    player,
                    payload.requestId(),
                    payload.toolName(),
                    payload.argumentsJson()
            ));
        });
    }

    private static void runOnPlayerServer(ServerPlayer player, Runnable task) {
        if (player == null || task == null) {
            return;
        }
        MinecraftServer server = P2SGameCompat.serverOf(player);
        if (server != null) {
            server.execute(task);
        }
    }

    public static void sendToClient(ServerPlayer player, P2SPacket payload) {
        P2SServerNetworkCompat.sendToClient(player, payload);
    }
}
