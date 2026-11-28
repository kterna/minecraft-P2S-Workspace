package com.p2s.compat;

import com.p2s.mixin.client.P2SEditBoxAccess;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Base EditBox adapter for MC 26.3+. */
public class P2SEditBoxBase extends EditBox {
    public P2SEditBoxBase(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        p2sRenderWidget(new P2SGfx(gfx), mouseX, mouseY, partialTick);
    }

    protected void p2sRenderWidget(P2SGfx gfx, int mouseX, int mouseY, float partialTick) {
        super.extractWidgetRenderState(gfx.unwrap(), mouseX, mouseY, partialTick);
    }

    protected FormattedCharSequence p2sFormat(String text, int offset) {
        return ((P2SEditBoxAccess) this).p2s$applyFormat(text, offset);
    }
}
