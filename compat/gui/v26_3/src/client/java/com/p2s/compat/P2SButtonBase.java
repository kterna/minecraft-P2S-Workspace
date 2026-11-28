package com.p2s.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Base Button adapter for MC 26.3+. */
public abstract class P2SButtonBase extends Button {
    private InputWithModifiers activePressInput;

    protected P2SButtonBase(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean isDoubleClick) {
        p2sOnClick(event.x(), event.y());
    }

    protected void p2sOnClick(double mouseX, double mouseY) {
        p2sSuperOnClick(mouseX, mouseY);
    }

    protected final void p2sSuperOnClick(double mouseX, double mouseY) {
        super.onClick(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(0, 0)), false);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        InputWithModifiers prev = this.activePressInput;
        this.activePressInput = input;
        try {
            p2sOnPress();
        } finally {
            this.activePressInput = prev;
        }
    }

    protected void p2sOnPress() {
        p2sSuperOnPress();
    }

    protected final void p2sSuperOnPress() {
        super.onPress(activePressInput != null ? activePressInput : new KeyEvent(0, 0, 0));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        p2sRenderWidget(new P2SGfx(gfx), mouseX, mouseY, partialTick);
    }

    protected abstract void p2sRenderWidget(P2SGfx gfx, int mouseX, int mouseY, float partialTick);
}
