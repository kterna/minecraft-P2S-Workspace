package com.p2s;

import com.p2s.compat.P2SClientLifecycleCompat;
import com.p2s.compat.P2SInputCompat;
import net.minecraft.client.KeyMapping;

public final class ModKeyBindings {
    public static final KeyMapping OPEN_CHAT = P2SInputCompat.registerKeyMapping(
            "key.p2s.open_chat", P2SInputCompat.KEY_O, "category.p2s"
    );

    private ModKeyBindings() {
    }

    public static void registerTickHandler() {
        P2SClientLifecycleCompat.registerEndClientTick(client -> {
            while (OPEN_CHAT.consumeClick()) {
                var screen = P2SClientLifecycleCompat.currentScreen(client);
                if (screen instanceof P2SWorkbenchScreen || screen instanceof P2SChatScreen) {
                    P2SClientLifecycleCompat.setScreen(client, null);
                } else {
                    // The classic three-dock screen stays reachable from the workbench's ⋯ menu.
                    P2SClientLifecycleCompat.setScreen(client, new P2SWorkbenchScreen());
                }
            }
        });
    }
}
