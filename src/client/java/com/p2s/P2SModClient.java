package com.p2s;

import com.p2s.network.P2SNetworkPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class P2SModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        P2SClientConfig.reload();
        ClientThemes.initialize();
        com.p2s.agent.tool.ToolExtensions.load();
        P2SNetworkPayloads.register();
        ClientNetworkHandler.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientServerBridge.onJoin();
            ClientAgentManager.onClientJoin();
            ClientThemes.flushMessages(client);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ClientServerBridge.onDisconnect();
            ClientAgentManager.onClientDisconnect();
        }));
        ClientDiagnostics.registerCommand();
        ModKeyBindings.registerTickHandler();
        SelectionInputHandler.register();
        SelectionRenderer.register();
        HudOverlay.register();
    }
}
