package com.p2s.compat;

import com.p2s.mixin.client.P2SEditBoxAccess;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Base EditBox adapter isolating render and input differences across Minecraft versions. */
public class P2SEditBoxBase extends EditBox {
    public P2SEditBoxBase(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        p2sRenderWidget(new P2SGfx(gfx), mouseX, mouseY, partialTick);
    }

    protected void p2sRenderWidget(P2SGfx gfx, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(gfx.unwrap(), mouseX, mouseY, partialTick);
    }

    protected FormattedCharSequence p2sFormat(String text, int offset) {
        return ((P2SEditBoxAccess) this).p2s$formatter().apply(text, offset);
    }
}
