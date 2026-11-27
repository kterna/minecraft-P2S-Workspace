package com.p2s.compat;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import java.util.function.Consumer;

/** HUD render registration compat for MC 1.14-1.21.5. */
public final class P2SHudCompat {
    private P2SHudCompat() {
    }

    public static void register(Consumer<P2SGfx> renderer) {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> renderer.accept(new P2SGfx(drawContext)));
    }
}
