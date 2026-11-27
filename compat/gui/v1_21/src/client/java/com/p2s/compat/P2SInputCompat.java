package com.p2s.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Keyboard, mouse, clipboard, and keybinding compat for MC 1.21-1.21.8. */
public final class P2SInputCompat {
    private P2SInputCompat() {
    }

    public static boolean hasShiftDown() {
        return Screen.hasShiftDown();
    }

    public static boolean hasControlDown() {
        return Screen.hasControlDown();
    }

    public static boolean isLeftMouseDown(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        return GLFW.glfwGetMouseButton(client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    public static String getClipboard(Minecraft client) {
        return client == null ? "" : client.keyboardHandler.getClipboard();
    }

    public static void setClipboard(Minecraft client, String text) {
        if (client != null) {
            client.keyboardHandler.setClipboard(text);
        }
    }

    public static KeyMapping registerKeyMapping(String translationKey, int keyCode, String categoryKey) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyMapping(translationKey, InputConstants.Type.KEYSYM, keyCode, categoryKey)
        );
    }

    public static boolean matchesKey(KeyMapping mapping, int keyCode, int scanCode) {
        return mapping != null && mapping.matches(keyCode, scanCode);
    }

    public static void setAllKeyStates() {
        KeyMapping.setAll();
    }

    public static void releaseAllKeys() {
        KeyMapping.releaseAll();
    }
}
