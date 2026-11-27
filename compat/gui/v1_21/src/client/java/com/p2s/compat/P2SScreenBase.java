package com.p2s.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base Screen adapter normalizing input/render overrides across Minecraft versions. */
public abstract class P2SScreenBase extends Screen {
    protected P2SScreenBase(Component title) {
        super(title);
    }

    public static boolean hasShiftDown() {
        return P2SInputCompat.hasShiftDown();
    }

    public static boolean hasControlDown() {
        return P2SInputCompat.hasControlDown();
    }

    @Override
    public void resize(Minecraft client, int width, int height) {
        p2sResize(client, width, height);
    }

    protected void p2sResize(Minecraft client, int width, int height) {
        p2sSuperResize(client, width, height);
    }

    protected final void p2sSuperResize(Minecraft client, int width, int height) {
        super.resize(client, width, height);
    }

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        p2sRenderBackground(new P2SGfx(gfx), mouseX, mouseY, delta);
    }

    public void p2sRenderBackground(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        p2sSuperRenderBackground(gfx, mouseX, mouseY, delta);
    }

    protected final void p2sSuperRenderBackground(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx.unwrap(), mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        p2sRender(new P2SGfx(gfx), mouseX, mouseY, delta);
    }

    public void p2sRender(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        p2sSuperRender(gfx, mouseX, mouseY, delta);
    }

    protected final void p2sSuperRender(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        super.render(gfx.unwrap(), mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return p2sKeyPressed(keyCode, scanCode, modifiers);
    }

    public boolean p2sKeyPressed(int keyCode, int scanCode, int modifiers) {
        return p2sSuperKeyPressed(keyCode, scanCode, modifiers);
    }

    protected final boolean p2sSuperKeyPressed(int keyCode, int scanCode, int modifiers) {
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return p2sCharTyped(codePoint, modifiers);
    }

    public boolean p2sCharTyped(char codePoint, int modifiers) {
        return p2sSuperCharTyped(codePoint, modifiers);
    }

    protected final boolean p2sSuperCharTyped(char codePoint, int modifiers) {
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return p2sMouseClicked(mouseX, mouseY, button);
    }

    public boolean p2sMouseClicked(double mouseX, double mouseY, int button) {
        return p2sSuperMouseClicked(mouseX, mouseY, button);
    }

    protected final boolean p2sSuperMouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return p2sMouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean p2sMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return p2sSuperMouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    protected final boolean p2sSuperMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return p2sMouseReleased(mouseX, mouseY, button);
    }

    public boolean p2sMouseReleased(double mouseX, double mouseY, int button) {
        return p2sSuperMouseReleased(mouseX, mouseY, button);
    }

    protected final boolean p2sSuperMouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return p2sMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public boolean p2sMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return p2sSuperMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    protected final boolean p2sSuperMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
