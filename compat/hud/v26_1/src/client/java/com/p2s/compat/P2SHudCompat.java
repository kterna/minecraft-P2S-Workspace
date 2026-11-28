package com.p2s.compat;

import com.p2s.P2SMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/** HUD render registration compat for MC 26.1+ (GuiGraphicsExtractor). */
public final class P2SHudCompat {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(P2SMod.MOD_ID, "hud");

    private P2SHudCompat() {
    }

    public static void register(Consumer<P2SGfx> renderer) {
        HudElementRegistry.addLast(HUD_ID, (gfx, tickCounter) -> renderer.accept(new P2SGfx(gfx)));
    }
}
