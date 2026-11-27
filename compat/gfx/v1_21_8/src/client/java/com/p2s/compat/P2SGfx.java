package com.p2s.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** 2D UI graphics facade for MC 1.21.6-1.21.8 (void drawString and nextStratum popup layer). */
public final class P2SGfx {
    private final GuiGraphics raw;

    public P2SGfx(GuiGraphics raw) {
        this.raw = raw;
    }

    public GuiGraphics unwrap() {
        return raw;
    }

    public void fill(int x1, int y1, int x2, int y2, int argb) {
        raw.fill(x1, y1, x2, y2, argb);
    }

    public int drawString(Font font, String text, int x, int y, int argb, boolean shadow) {
        raw.drawString(font, text, x, y, argb, shadow);
        return x + font.width(text);
    }

    public int drawString(Font font, Component text, int x, int y, int argb, boolean shadow) {
        raw.drawString(font, text, x, y, argb, shadow);
        return x + font.width(text);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int argb, boolean shadow) {
        raw.drawString(font, text, x, y, argb, shadow);
        return x + font.width(text);
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        raw.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        raw.disableScissor();
    }

    public void renderOutline(int x, int y, int width, int height, int argb) {
        raw.renderOutline(x, y, width, height, argb);
    }

    public void renderItem(ItemStack stack, int x, int y) {
        raw.renderItem(stack, x, y);
    }

    public void pushPopupLayer() {
        raw.nextStratum();
    }

    public void popPopupLayer() {
    }
}
