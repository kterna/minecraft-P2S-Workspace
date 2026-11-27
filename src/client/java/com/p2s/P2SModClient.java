package com.p2s;

import com.p2s.network.P2SNetworkPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class P2SModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        P2SClientConfig.reload();
        com.p2s.agent.tool.ToolExtensions.load();
        P2SNetworkPayloads.register();
        ClientNetworkHandler.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientServerBridge.onJoin();
            ClientAgentManager.onClientJoin();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ClientServerBridge.onDisconnect();
            ClientAgentManager.onClientDisconnect();
            com.p2s.preview.OrbitCamera.stop();
            com.p2s.preview.ClientPreviewStore.clear();
        }));
        ClientDiagnostics.registerCommand();
        ModKeyBindings.registerTickHandler();
        SelectionInputHandler.register();
        SelectionRenderer.register();
        com.p2s.preview.GhostBlockRenderer.register();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                client -> com.p2s.preview.ClientPreviewStore.tick());
        HudOverlay.register();
    }
}
