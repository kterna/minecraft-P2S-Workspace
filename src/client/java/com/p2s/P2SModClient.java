package com.p2s;

import com.p2s.compat.P2SClientLifecycleCompat;
import com.p2s.network.P2SNetworkPayloads;
import net.fabricmc.api.ClientModInitializer;

public class P2SModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        P2SClientConfig.reload();
        ClientThemes.initialize();
        com.p2s.agent.tool.ToolExtensions.load();
        P2SNetworkPayloads.register();
        ClientNetworkHandler.register();
        P2SClientLifecycleCompat.registerJoin(client -> {
            ClientServerBridge.onJoin();
            ClientAgentManager.onClientJoin();
            ClientThemes.flushMessages(client);
        });
        P2SClientLifecycleCompat.registerDisconnect(client -> {
            ClientServerBridge.onDisconnect();
            ClientAgentManager.onClientDisconnect();
            com.p2s.preview.OrbitCamera.stop();
            com.p2s.preview.ClientPreviewStore.clear();
        });
        ClientDiagnostics.registerCommand();
        ModKeyBindings.registerTickHandler();
        SelectionInputHandler.register();
        SelectionRenderer.register();
        com.p2s.preview.GhostBlockRenderer.register();
        P2SClientLifecycleCompat.registerEndClientTick(client -> com.p2s.preview.ClientPreviewStore.tick());
        HudOverlay.register();
    }
}
