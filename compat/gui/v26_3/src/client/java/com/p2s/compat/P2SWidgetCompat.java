package com.p2s.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Widget helper isolating EditBox/Button API differences for MC 26.3+. */
public final class P2SWidgetCompat {
    private P2SWidgetCompat() {
    }

    public static void setTooltip(AbstractWidget widget, Component tooltip) {
        if (widget == null) {
            return;
        }
        widget.setTooltip(tooltip == null ? null : Tooltip.create(tooltip));
    }

    public static boolean keyPressed(EditBox box, int keyCode, int scanCode, int modifiers) {
        return box != null && box.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }

    public static boolean charTyped(EditBox box, char codePoint, int modifiers) {
        return box != null && box.charTyped(new CharacterEvent(codePoint));
    }

    public static void moveCursorToEnd(EditBox box, boolean select) {
        if (box != null) {
            box.moveCursorToEnd(select);
        }
    }

    public static void setHint(EditBox box, Component hint) {
        if (box != null) {
            box.setHint(hint);
        }
    }
}
