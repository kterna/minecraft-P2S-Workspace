package com.p2s.theme;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class ThemeCatalog {
    static final String BUILTIN_RESOURCE = "/assets/prompt2structure/themes/themes.json";
    private final ThemePack builtin;
    private final ThemePack.Definition defaultTheme;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    // A transient malformed edit must not destroy a player's last successfully loaded override.
    private Map<Path, ThemePack> lastGood = Map.of();
    private volatile State state;

    private record State(Map<String, P2STheme> themes, P2STheme current) {}

    private ThemeCatalog(ThemePack builtin) {
        this.builtin = builtin;
        this.defaultTheme = builtin.themes().stream().filter(t -> t.id().equals(builtin.defaultId())).findFirst().orElseThrow();
        Map<String, P2STheme> themes = palettes(List.of());
        this.state = new State(themes, themes.get(builtin.defaultId()));
    }

    static ThemeCatalog fromResources() {
        var stream = ThemeCatalog.class.getResourceAsStream(BUILTIN_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Missing built-in themes: " + BUILTIN_RESOURCE);
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new ThemeCatalog(ThemePack.read(reader, true));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid built-in themes: " + BUILTIN_RESOURCE, e);
        }
    }

    P2STheme current() {
        return state.current();
    }

    List<P2STheme> available() {
        return List.copyOf(state.themes().values());
    }

    void addListener(Runnable listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener));
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    synchronized boolean select(String id) {
        P2STheme next = state.themes().get(id);
        if (next == null) {
            return false;
        }
        publish(state.themes(), next);
        return true;
    }

    synchronized void reload(Path directory, String requestedId, Consumer<String> report) {
        List<Path> files;
        try {
            Files.createDirectories(directory);
            try (var list = Files.list(directory)) {
                files = list.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .map(path -> path.toAbsolutePath().normalize()).sorted().toList();
            }
        } catch (IOException | UncheckedIOException | SecurityException e) {
            report.accept(directory.getFileName() + " [$] " + e.getMessage());
            // On the first startup the built-in choice still applies even if the directory is inaccessible.
            publishRequested(state.themes(), requestedId, report);
            return;
        }

        var loaded = new LinkedHashMap<Path, ThemePack>();
        for (Path path : files) {
            ThemePack pack;
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                pack = ThemePack.read(reader, false);
            } catch (IOException | IllegalArgumentException | SecurityException e) {
                String detail = e.getMessage();
                String field = detail != null && detail.startsWith("[") ? "" : "[$] ";
                report.accept(path.getFileName() + " " + field + detail);
                pack = lastGood.get(path);
            }
            if (pack != null) {
                loaded.put(path, pack);
            }
        }
        lastGood = Map.copyOf(loaded);
        publishRequested(palettes(loaded.values()), requestedId, report);
    }

    private void publishRequested(Map<String, P2STheme> themes, String requestedId, Consumer<String> report) {
        String id = requestedId == null || requestedId.isBlank() ? builtin.defaultId() : requestedId;
        P2STheme next = themes.get(id);
        if (next == null) {
            report.accept("p2s_client.json [theme] unknown theme id: " + id);
            next = themes.getOrDefault(current().id(), themes.get(builtin.defaultId()));
        }
        publish(themes, next);
    }

    private Map<String, P2STheme> palettes(Iterable<ThemePack> userPacks) {
        var definitions = new LinkedHashMap<String, ThemePack.Definition>();
        builtin.themes().forEach(theme -> definitions.put(theme.id(), theme));
        for (ThemePack pack : userPacks) {
            pack.themes().forEach(theme -> definitions.put(theme.id(), theme));
        }
        // Only the built-in pack chooses the global default; its palette can itself be overridden.
        var defaults = new HashMap<>(defaultTheme.colors());
        defaults.putAll(definitions.get(builtin.defaultId()).colors());
        var themes = new LinkedHashMap<String, P2STheme>();
        for (ThemePack.Definition theme : definitions.values()) {
            var colors = new HashMap<>(defaults);
            colors.putAll(theme.colors());
            themes.put(theme.id(), new P2STheme(theme.id(), theme.name(), colors));
        }
        return Collections.unmodifiableMap(themes);
    }

    private void publish(Map<String, P2STheme> themes, P2STheme next) {
        boolean changed = !state.current().samePalette(next);
        state = new State(themes, next);
        if (changed) {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    System.getLogger(ThemeCatalog.class.getName()).log(System.Logger.Level.WARNING, "Theme change listener failed", e);
                }
            }
        }
    }
}
