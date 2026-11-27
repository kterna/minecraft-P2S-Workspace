package com.p2s.theme;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** An immutable, fully resolved set of interface colours. */
public final class P2STheme {
    private final String id;
    private final String name;
    private final Map<String, Integer> colors;

    P2STheme(String id, String name, Map<String, Integer> colors) {
        this.id = id;
        this.name = name;
        this.colors = Map.copyOf(colors);
    }

    private static final class Holder {
        private static final ThemeCatalog CATALOG = ThemeCatalog.fromResources();
    }

    public static P2STheme current() {
        return Holder.CATALOG.current();
    }

    public static List<P2STheme> available() {
        return Holder.CATALOG.available();
    }

    public static boolean select(String id) {
        return Holder.CATALOG.select(id);
    }

    /** Reads player packs from the given p2s_themes directory. The caller delivers errors to log and chat. */
    public static void reload(Path themesDirectory, String requestedId, Consumer<String> reportError) {
        Holder.CATALOG.reload(themesDirectory, requestedId, reportError);
    }

    public static void addListener(Runnable listener) {
        Holder.CATALOG.addListener(listener);
    }

    public static void removeListener(Runnable listener) {
        Holder.CATALOG.removeListener(listener);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Returns the Minecraft ARGB representation, including the original palette alpha. */
    public int color(String field) {
        Integer value = colors.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Unknown P2S theme color: " + field);
        }
        return value;
    }

    boolean samePalette(P2STheme other) {
        return id.equals(other.id) && name.equals(other.name) && colors.equals(other.colors);
    }
}
