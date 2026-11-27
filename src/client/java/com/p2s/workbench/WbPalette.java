package com.p2s.workbench;

import java.util.Map;

/**
 * Workbench colours by semantic name, as ARGB. Until the theme system (feat/ui-theme) is merged
 * this serves the default "余烬" theme from docs/mockups/gpt/themes.json; afterwards
 * {@link #color} delegates to {@code P2STheme.current().color(name)}.
 */
public final class WbPalette {
    private static final Map<String, Integer> EMBER = Map.ofEntries(
            Map.entry("backdrop", 0xFF202624),
            Map.entry("inset", 0xFF262C29),
            Map.entry("panel", 0xFF2B322E),
            Map.entry("raised", 0xFF333935),
            Map.entry("line", 0xFF4E544E),
            Map.entry("text", 0xFFC5C6B7),
            Map.entry("muted", 0xFFAFB1A4),
            Map.entry("faint", 0xFF9A9D91),
            Map.entry("accent", 0xFFD4AD84),
            Map.entry("on-accent", 0xFF302A24),
            Map.entry("hover", 0x0BF1EEE4),
            Map.entry("pressed", 0x17F1EEE4),
            Map.entry("focus", 0xFFD2B18C),
            Map.entry("input-bg", 0xFF242A28),
            Map.entry("input-border", 0xFF5C615A),
            Map.entry("selected", 0xFF3E4037),
            Map.entry("scrollbar", 0xFF6A6F66),
            Map.entry("success", 0xFFA0BE9B),
            Map.entry("warning", 0xFFCDB086),
            Map.entry("danger", 0xFFD9A69E),
            Map.entry("info", 0xFF97B9CF),
            Map.entry("diff-add-bg", 0xFF38423B),
            Map.entry("diff-add-text", 0xFFA0BE9B),
            Map.entry("diff-remove-bg", 0xFF3F3F3B),
            Map.entry("diff-remove-text", 0xFFD9A69E),
            Map.entry("tool-done", 0xFFA0BE9B),
            Map.entry("tool-running", 0xFFD4AD84),
            Map.entry("tool-waiting", 0xFFA9AB9E),
            Map.entry("tool-failed", 0xFFD9A69E),
            Map.entry("preview-add", 0xFFAAC4A3),
            Map.entry("preview-remove", 0xFFDBABA1),
            Map.entry("preview-file", 0xFFD4AD84),
            Map.entry("overlay", 0xE6272E2B),
            Map.entry("on-overlay", 0xFFC5C6B7),
            Map.entry("text-shadow", 0x55151816)
    );

    private WbPalette() {
    }

    public static int color(String name) {
        Integer value = EMBER.get(name);
        return value == null ? 0xFFFF00FF : value;
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
