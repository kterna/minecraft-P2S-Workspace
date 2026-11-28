package com.p2s.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.p2s.P2SMod;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;

/** Keyboard, mouse, clipboard, and keybinding compat for MC 26.3+ (SDL3 InputConstants). */
public final class P2SInputCompat {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(P2SMod.MOD_ID, "main"));

    public static final int KEY_ESCAPE = InputConstants.KEY_ESCAPE;
    public static final int KEY_ENTER = InputConstants.KEY_RETURN;
    public static final int KEY_KP_ENTER = InputConstants.KEY_NUMPADENTER;
    public static final int KEY_BACKSPACE = InputConstants.KEY_BACKSPACE;
    public static final int KEY_DELETE = InputConstants.KEY_DELETE;
    public static final int KEY_TAB = InputConstants.KEY_TAB;
    public static final int KEY_UP = InputConstants.KEY_UP;
    public static final int KEY_DOWN = InputConstants.KEY_DOWN;
    public static final int KEY_LEFT = InputConstants.KEY_LEFT;
    public static final int KEY_RIGHT = InputConstants.KEY_RIGHT;
    public static final int KEY_HOME = InputConstants.KEY_HOME;
    public static final int KEY_END = InputConstants.KEY_END;
    public static final int KEY_PAGE_UP = InputConstants.KEY_PAGEUP;
    public static final int KEY_PAGE_DOWN = InputConstants.KEY_PAGEDOWN;
    public static final int KEY_F7 = InputConstants.KEY_F7;
    public static final int KEY_O = InputConstants.KEY_O;
    public static final int KEY_K = InputConstants.KEY_K;
    public static final int DEFAULT_OPEN_CHAT_KEY = InputConstants.KEY_K;
    public static final int KEY_1 = InputConstants.KEY_1;
    public static final int KEY_3 = InputConstants.KEY_3;
    public static final int KEY_A = InputConstants.KEY_A;
    public static final int KEY_C = InputConstants.KEY_C;
    public static final int KEY_V = InputConstants.KEY_V;
    public static final int KEY_X = InputConstants.KEY_X;
    public static final int MOD_ALT = InputConstants.MOD_ALT;

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
        return client != null && client.mouseHandler != null && client.mouseHandler.isLeftPressed();
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
                new KeyMapping(translationKey, InputConstants.Type.KEYBOARD, keyCode, CATEGORY)
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
