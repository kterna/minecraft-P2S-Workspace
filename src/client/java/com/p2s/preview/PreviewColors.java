package com.p2s.preview;

/**
 * Preview colours as RGB triples. Defaults are the {@code preview-*} fields of the default
 * "余烬" theme in docs/mockups/gpt/themes.json; once the theme system is merged these read
 * {@code P2STheme.current().color(...)} instead.
 */
final class PreviewColors {
    private static final int[] ADD = rgb(0xAAC4A3);
    private static final int[] REMOVE = rgb(0xDBABA1);
    private static final int[] FILE = rgb(0xD4AD84);

    private PreviewColors() {
    }

    static int[] add() {
        return ADD;
    }

    static int[] remove() {
        return REMOVE;
    }

    static int[] file() {
        return FILE;
    }

    private static int[] rgb(int color) {
        return new int[]{(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
    }
}
