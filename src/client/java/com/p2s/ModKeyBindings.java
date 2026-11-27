package com.p2s;

import com.p2s.compat.P2SClientLifecycleCompat;
import com.p2s.compat.P2SInputCompat;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class ModKeyBindings {
    public static final KeyMapping OPEN_CHAT = P2SInputCompat.registerKeyMapping(
            "key.p2s.open_chat", GLFW.GLFW_KEY_O, "category.p2s"
    );

    private ModKeyBindings() {
    }

    public static void registerTickHandler() {
        P2SClientLifecycleCompat.registerEndClientTick(client -> {
            while (OPEN_CHAT.consumeClick()) {
                if (client.screen instanceof P2SWorkbenchScreen || client.screen instanceof P2SChatScreen) {
                    client.setScreen(null);
                } else {
                    // The classic three-dock screen stays reachable from the workbench's ⋯ menu.
                    client.setScreen(new P2SWorkbenchScreen());
                }
            }
        });
    }
}
