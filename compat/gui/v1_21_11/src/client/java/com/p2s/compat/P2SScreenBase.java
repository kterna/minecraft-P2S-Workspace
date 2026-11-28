package com.p2s.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Base Screen adapter for MC 1.21.9+ / 1.21.11+ (KeyEvent, MouseButtonEvent, CharacterEvent, 2-arg resize). */
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
    public void resize(int width, int height) {
        p2sResize(this.minecraft, width, height);
    }

    protected void p2sResize(Minecraft client, int width, int height) {
        p2sSuperResize(client, width, height);
    }

    protected final void p2sSuperResize(Minecraft client, int width, int height) {
        super.resize(width, height);
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
    public boolean keyPressed(KeyEvent event) {
        return p2sKeyPressed(event.key(), event.scancode(), event.modifiers());
    }

    public boolean p2sKeyPressed(int keyCode, int scanCode, int modifiers) {
        return p2sSuperKeyPressed(keyCode, scanCode, modifiers);
    }

    protected final boolean p2sSuperKeyPressed(int keyCode, int scanCode, int modifiers) {
        return super.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return p2sCharTyped((char) event.codepoint(), event.modifiers());
    }

    public boolean p2sCharTyped(char codePoint, int modifiers) {
        return p2sSuperCharTyped(codePoint, modifiers);
    }

    protected final boolean p2sSuperCharTyped(char codePoint, int modifiers) {
        return super.charTyped(new CharacterEvent(codePoint, modifiers));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        return p2sMouseClicked(event.x(), event.y(), event.button());
    }

    public boolean p2sMouseClicked(double mouseX, double mouseY, int button) {
        return p2sSuperMouseClicked(mouseX, mouseY, button);
    }

    protected final boolean p2sSuperMouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return p2sMouseDragged(event.x(), event.y(), event.button(), dragX, dragY);
    }

    public boolean p2sMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return p2sSuperMouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    protected final boolean p2sSuperMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return p2sMouseReleased(event.x(), event.y(), event.button());
    }

    public boolean p2sMouseReleased(double mouseX, double mouseY, int button) {
        return p2sSuperMouseReleased(mouseX, mouseY, button);
    }

    protected final boolean p2sSuperMouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)));
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
