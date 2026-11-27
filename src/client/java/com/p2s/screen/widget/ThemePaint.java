package com.p2s.screen.widget;

import com.p2s.compat.P2SGameCompat;
import com.p2s.compat.P2SGfx;
import com.p2s.theme.P2STheme;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

/** Paint helpers shared by P2S screens. Theme values are resolved at draw time. */
public final class ThemePaint {
    private ThemePaint() {}

    public static int color(String field) {
        return P2STheme.current().color(field);
    }

    public static int alpha(int argb, float opacity) {
        int a = Math.round((argb >>> 24) * Math.clamp(opacity, 0f, 1f));
        return a << 24 | argb & 0x00ffffff;
    }

    public static void border(P2SGfx gfx, int x, int y, int width, int height, int argb) {
        gfx.renderOutline(x, y, width, height, argb);
    }

    public static int text(P2SGfx gfx, Font font, String value, int x, int y, int argb, boolean shadow) {
        if ((argb >>> 24) < 4) return x + font.width(value);
        if (shadow) {
            int shade = alpha(color("text-shadow"), (argb >>> 24) / 255f);
            if ((shade >>> 24) >= 4) gfx.drawString(font, value, x + 1, y + 1, shade, false);
        }
        return gfx.drawString(font, value, x, y, argb, false);
    }

    public static int text(P2SGfx gfx, Font font, Component value, int x, int y, int argb, boolean shadow) {
        return text(gfx, font, value.getVisualOrderText(), x, y, argb, shadow);
    }

    public static int text(P2SGfx gfx, Font font, FormattedCharSequence value, int x, int y,
                           int argb, boolean shadow) {
        if ((argb >>> 24) < 4) return x + font.width(value);
        if (shadow) {
            int shade = alpha(color("text-shadow"), (argb >>> 24) / 255f);
            if ((shade >>> 24) >= 4) {
                FormattedCharSequence silhouette = sink -> value.accept((i, style, cp) ->
                        sink.accept(i, style.withColor((TextColor) null), cp));
                gfx.drawString(font, silhouette, x + 1, y + 1, shade, false);
            }
        }
        return gfx.drawString(font, value, x, y, argb, false);
    }

    /** Current opacity of a transient layer; changing direction uses the current painted opacity. */
    public static final class Transition {
        private boolean target;
        private float from;
        private long changedAt;

        public float value(boolean enabled) {
            long now = P2SGameCompat.millis();
            float t = Math.clamp((now - changedAt) / 100f, 0f, 1f);
            float current = from + ((target ? 1f : 0f) - from) * t;
            if (target != enabled) {
                target = enabled;
                from = current;
                changedAt = now;
            }
            return current;
        }
    }
}
