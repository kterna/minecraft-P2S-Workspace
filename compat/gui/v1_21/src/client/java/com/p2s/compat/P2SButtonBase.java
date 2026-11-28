package com.p2s.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Base Button adapter isolating Button constructor, click event, and render signature changes. */
public abstract class P2SButtonBase extends Button {
    protected P2SButtonBase(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        p2sOnClick(mouseX, mouseY);
    }

    protected void p2sOnClick(double mouseX, double mouseY) {
        p2sSuperOnClick(mouseX, mouseY);
    }

    protected final void p2sSuperOnClick(double mouseX, double mouseY) {
        super.onClick(mouseX, mouseY);
    }

    @Override
    public void onPress() {
        p2sOnPress();
    }

    protected void p2sOnPress() {
        p2sSuperOnPress();
    }

    protected final void p2sSuperOnPress() {
        super.onPress();
    }

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        p2sRenderWidget(new P2SGfx(gfx), mouseX, mouseY, partialTick);
    }

    protected abstract void p2sRenderWidget(P2SGfx gfx, int mouseX, int mouseY, float partialTick);

    public void p2sSetTooltip(Component tip) {
        setTooltip(tip == null ? null : Tooltip.create(tip));
    }
}
