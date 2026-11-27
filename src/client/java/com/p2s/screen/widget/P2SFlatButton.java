package com.p2s.screen.widget;

import com.p2s.compat.P2SButtonBase;
import com.p2s.compat.P2SGameCompat;
import com.p2s.compat.P2SGfx;
import com.p2s.compat.P2SInputCompat;
import com.p2s.compat.P2SWidgetCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import static com.p2s.screen.widget.ThemePaint.color;

public class P2SFlatButton extends P2SButtonBase {
    public enum Variant { NORMAL, PRIMARY, DANGER, MUTED }

    private final Variant variant;
    private final ThemePaint.Transition hover = new ThemePaint.Transition();
    private final ThemePaint.Transition press = new ThemePaint.Transition();
    private final ThemePaint.Transition selection = new ThemePaint.Transition();
    private boolean selected;
    private boolean mouseDown;
    private long pressedUntil;

    public P2SFlatButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        this(x, y, width, height, message, onPress, Variant.NORMAL);
    }

    public P2SFlatButton(int x, int y, int width, int height, Component message, OnPress onPress, Variant variant) {
        super(x, y, width, height, message, onPress);
        this.variant = variant == null ? Variant.NORMAL : variant;
    }

    public static Factory themed(Component label, OnPress action) {
        return new Factory(label, action);
    }

    public static final class Factory {
        private final Component label;
        private final OnPress action;
        private int x, y, width = 150, height = 20;
        private Component tooltip;
        private Variant variant = Variant.NORMAL;

        private Factory(Component label, OnPress action) {
            this.label = label;
            this.action = action;
        }

        public Factory bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Factory tooltip(Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Factory variant(Variant variant) {
            this.variant = variant;
            return this;
        }

        public P2SFlatButton build() {
            P2SFlatButton button = new P2SFlatButton(x, y, width, height, label, action, variant);
            if (tooltip != null) P2SWidgetCompat.setTooltip(button, tooltip);
            return button;
        }
    }

    public static void setSelected(Button button, boolean selected) {
        if (button instanceof P2SFlatButton flat) flat.selected = selected;
    }

    @Override
    protected void p2sOnClick(double mouseX, double mouseY) {
        mouseDown = true;
        p2sSuperOnClick(mouseX, mouseY);
    }

    @Override
    public void onPress() {
        pressedUntil = P2SGameCompat.millis() + 100;
        super.onPress();
    }

    @Override
    protected void p2sRenderWidget(P2SGfx gfx, int mouseX, int mouseY, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (mouseDown && !P2SInputCompat.isLeftMouseDown(client)) {
            mouseDown = false;
        }
        int x = getX(), y = getY(), right = x + width, bottom = y + height;
        boolean primary = variant == Variant.PRIMARY && active;
        int bg = color(primary ? "accent" : variant == Variant.MUTED ? "inset" : "raised");
        int ink = color(selected ? "accent" : !active ? "faint" : primary ? "on-accent" : variant == Variant.DANGER ? "danger" : "text");
        gfx.fill(x, y, right, bottom, bg);
        gfx.fill(x, y, right, bottom, ThemePaint.alpha(color("selected"), selection.value(selected)));
        gfx.fill(x, y, right, bottom, ThemePaint.alpha(color("hover"), hover.value(active && isHovered())));
        gfx.fill(x, y, right, bottom, ThemePaint.alpha(color("pressed"), press.value(active && (mouseDown || P2SGameCompat.millis() < pressedUntil))));
        ThemePaint.border(gfx, x, y, width, height, color(isFocused() ? "focus" : primary ? "accent" : "line"));
        if (selected) gfx.fill(x, y + 1, x + 1, bottom - 1, color("accent"));
        drawLabel(gfx, client.font, ink);
    }

    private void drawLabel(P2SGfx gfx, Font font, int ink) {
        int left = getX() + 2, right = getX() + width - 2;
        if (left >= right) return;
        int textWidth = font.width(getMessage());
        int overflow = textWidth - (right - left);
        int y = getY() + (height - 8) / 2;
        if (overflow <= 0) {
            ThemePaint.text(gfx, font, getMessage(), getX() + (width - textWidth) / 2, y, ink, true);
            return;
        }
        // Vanilla buttons reveal long translated labels by scrolling them inside the button.
        double period = Math.max(overflow * 0.5, 3.0);
        double phase = Math.sin(Math.PI / 2 * Math.cos(2 * Math.PI * P2SGameCompat.millis() / 1000 / period)) / 2 + 0.5;
        gfx.enableScissor(left, getY(), right, getY() + height);
        ThemePaint.text(gfx, font, getMessage(), left - (int) (overflow * phase), y, ink, true);
        gfx.disableScissor();
    }
}
