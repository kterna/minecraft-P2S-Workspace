package com.p2s.compat;

import com.p2s.P2SMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

/** HUD render registration compat for MC 1.21.6-1.21.8 (HudElementRegistry). */
public final class P2SHudCompat {
    private static final ResourceLocation HUD_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "hud");

    private P2SHudCompat() {
    }

    public static void register(Consumer<P2SGfx> renderer) {
        HudElementRegistry.addLast(HUD_ID, (drawContext, tickCounter) -> renderer.accept(new P2SGfx(drawContext)));
    }
}
