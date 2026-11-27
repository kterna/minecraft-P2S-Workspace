package com.p2s.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Widget helper isolating EditBox/Button API differences across Minecraft versions. */
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
        return box != null && box.keyPressed(keyCode, scanCode, modifiers);
    }

    public static boolean charTyped(EditBox box, char codePoint, int modifiers) {
        return box != null && box.charTyped(codePoint, modifiers);
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
