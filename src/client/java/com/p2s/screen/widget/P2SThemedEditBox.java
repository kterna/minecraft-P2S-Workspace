package com.p2s.screen.widget;

import com.p2s.compat.P2SEditBoxBase;
import com.p2s.compat.P2SGameCompat;
import com.p2s.compat.P2SGfx;
import com.p2s.mixin.client.P2SEditBoxAccess;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import static com.p2s.screen.widget.ThemePaint.color;

/** A vanilla EditBox with the current P2S palette. Rendering never changes its text, focus or scroll. */
public class P2SThemedEditBox extends P2SEditBoxBase {
    private final Font font;
    private final ThemePaint.Transition hover = new ThemePaint.Transition();

    public P2SThemedEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        this.font = font;
    }

    @Override
    protected void p2sRenderWidget(P2SGfx gfx, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) return;
        int x = getX(), y = getY();
        int left = isBordered() ? x + 4 : x;
        int top = isBordered() ? y + (height - 8) / 2 : y;
        if (isBordered()) {
            gfx.fill(x, y, x + width, y + height, color("input-bg"));
            gfx.fill(x, y, x + width, y + height, ThemePaint.alpha(color("hover"), hover.value(active && isHovered())));
            ThemePaint.border(gfx, x, y, width, height, color(isFocused() ? "focus" : "input-border"));
        }
        P2SEditBoxAccess state = (P2SEditBoxAccess) this;
        String value = getValue();
        int offset = state.p2s$displayPos();
        String visibleText = font.plainSubstrByWidth(value.substring(offset), getInnerWidth());
        int cursor = getCursorPosition() - offset;
        int anchor = state.p2s$highlightPos() - offset;
        int cursorIndex = Math.clamp(cursor, 0, visibleText.length());
        String before = visibleText.substring(0, cursorIndex);
        FormattedCharSequence prefix = state.p2s$formatter().apply(before, offset);
        int caret = left + font.width(prefix);
        int ink = color(active && state.p2s$isEditable() ? "text" : "faint");

        gfx.enableScissor(x, y, x + width, y + height);
        if (anchor != cursor) {
            int anchorIndex = Math.clamp(anchor, 0, visibleText.length());
            int selectionEnd = left + font.width(state.p2s$formatter().apply(visibleText.substring(0, anchorIndex), offset));
            gfx.fill(Math.min(caret, selectionEnd), top - 1, Math.max(caret, selectionEnd), top + 9, color("selected"));
        }
        ThemePaint.text(gfx, font, prefix, left, top, ink, false);
        if (cursorIndex < visibleText.length()) {
            FormattedCharSequence suffix = state.p2s$formatter().apply(visibleText.substring(cursorIndex), offset + cursorIndex);
            ThemePaint.text(gfx, font, suffix, caret, top, ink, false);
        }
        if (value.isEmpty() && !isFocused() && state.p2s$hint() != null) {
            ThemePaint.text(gfx, font, state.p2s$hint(), left, top, color("faint"), false);
        }
        boolean insert = getCursorPosition() < value.length() || value.length() >= state.p2s$maxLength();
        if (!insert && state.p2s$suggestion() != null) {
            ThemePaint.text(gfx, font, state.p2s$suggestion(), caret, top, color("faint"), false);
        }
        if (isFocused() && cursor >= 0 && cursor <= visibleText.length() && (P2SGameCompat.millis() - state.p2s$focusedTime()) / 300 % 2 == 0) {
            if (insert) {
                gfx.fill(caret, top - 1, caret + 1, top + 9, color("focus"));
            } else {
                ThemePaint.text(gfx, font, "_", caret, top, color("focus"), false);
            }
        }
        gfx.disableScissor();
    }
}
