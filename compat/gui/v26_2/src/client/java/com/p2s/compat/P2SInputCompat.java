package com.p2s.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.p2s.P2SMod;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Keyboard, mouse, clipboard, and keybinding compat for MC 26.1+ (KeyMappingHelper). */
public final class P2SInputCompat {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(P2SMod.MOD_ID, "main"));
    public static final int KEY_ESCAPE = GLFW.GLFW_KEY_ESCAPE;
    public static final int KEY_ENTER = GLFW.GLFW_KEY_ENTER;
    public static final int KEY_KP_ENTER = GLFW.GLFW_KEY_KP_ENTER;
    public static final int KEY_BACKSPACE = GLFW.GLFW_KEY_BACKSPACE;
    public static final int KEY_DELETE = GLFW.GLFW_KEY_DELETE;
    public static final int KEY_TAB = GLFW.GLFW_KEY_TAB;
    public static final int KEY_UP = GLFW.GLFW_KEY_UP;
    public static final int KEY_DOWN = GLFW.GLFW_KEY_DOWN;
    public static final int KEY_LEFT = GLFW.GLFW_KEY_LEFT;
    public static final int KEY_RIGHT = GLFW.GLFW_KEY_RIGHT;
    public static final int KEY_HOME = GLFW.GLFW_KEY_HOME;
    public static final int KEY_END = GLFW.GLFW_KEY_END;
    public static final int KEY_PAGE_UP = GLFW.GLFW_KEY_PAGE_UP;
    public static final int KEY_PAGE_DOWN = GLFW.GLFW_KEY_PAGE_DOWN;
    public static final int KEY_F7 = GLFW.GLFW_KEY_F7;
    public static final int KEY_O = GLFW.GLFW_KEY_O;
    public static final int DEFAULT_OPEN_CHAT_KEY = GLFW.GLFW_KEY_K;
    public static final int KEY_1 = GLFW.GLFW_KEY_1;
    public static final int KEY_3 = GLFW.GLFW_KEY_3;
    public static final int KEY_A = GLFW.GLFW_KEY_A;
    public static final int KEY_C = GLFW.GLFW_KEY_C;
    public static final int KEY_V = GLFW.GLFW_KEY_V;
    public static final int KEY_X = GLFW.GLFW_KEY_X;
    public static final int MOD_ALT = GLFW.GLFW_MOD_ALT;

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
        return KeyMappingHelper.registerKeyMapping(
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
