package com.p2s.preview;

import com.p2s.theme.P2STheme;

/** Preview colours as RGB triples, read from the current theme's {@code preview-*} fields. */
final class PreviewColors {
    private PreviewColors() {
    }

    static int[] add() {
        return rgb("preview-add");
    }

    static int[] remove() {
        return rgb("preview-remove");
    }

    static int[] file() {
        return rgb("preview-file");
    }

    private static int[] rgb(String field) {
        int color = P2STheme.current().color(field);
        return new int[]{(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
    }
}
