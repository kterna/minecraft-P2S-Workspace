package com.p2s.workbench;

import com.p2s.theme.P2STheme;

/** Workbench colours by semantic name, as ARGB, from the current {@link P2STheme}. */
public final class WbPalette {
    private WbPalette() {
    }

    public static int color(String name) {
        return P2STheme.current().color(name);
    }

    /** Same colour with its alpha multiplied by {@code factor} (0..1). */
    public static int fade(int argb, float factor) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Linear blend from {@code a} to {@code b}; used for the 100 ms hover transitions. */
    public static int mix(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * k) & 0xFF) << shift;
        }
        return out;
    }
}
