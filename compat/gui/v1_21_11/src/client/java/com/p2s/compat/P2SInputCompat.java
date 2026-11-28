package com.p2s.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.p2s.P2SMod;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Keyboard, mouse, clipboard, and keybinding compat for MC 1.21.9+ / 1.21.11+. */
public final class P2SInputCompat {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(P2SMod.MOD_ID, "main"));

    private P2SInputCompat() {
    }

    public static boolean hasShiftDown() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.hasShiftDown();
    }

    public static boolean hasControlDown() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.hasControlDown();
    }

    public static boolean isLeftMouseDown(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        return GLFW.glfwGetMouseButton(client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
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
                new KeyMapping(translationKey, InputConstants.Type.KEYSYM, keyCode, CATEGORY)
        );
    }

    public static boolean matchesKey(KeyMapping mapping, int keyCode, int scanCode) {
        return mapping != null && mapping.matches(new KeyEvent(keyCode, scanCode, 0));
    }

    public static void setAllKeyStates() {
        KeyMapping.setAll();
    }

    public static void releaseAllKeys() {
        KeyMapping.releaseAll();
    }
}
