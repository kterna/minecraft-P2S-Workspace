package com.p2s.theme;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record ThemePack(String defaultId, List<Definition> themes) {
    static final Set<String> FIELDS = Set.of(
            "backdrop", "inset", "panel", "raised", "line", "text", "muted", "faint", "accent", "on-accent",
            "hover", "pressed", "focus", "input-bg", "input-border", "selected", "scrollbar", "success",
            "warning", "danger", "info", "diff-add-bg", "diff-add-text", "diff-remove-bg", "diff-remove-text",
            "tool-done", "tool-running", "tool-waiting", "tool-failed", "preview-add", "preview-remove",
            "preview-file", "overlay", "on-overlay", "text-shadow");
    private static final Gson GSON = new Gson();
    private static final Pattern CSS_COLOR = Pattern.compile("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?");

    record Definition(String id, String name, Map<String, Integer> colors) {}

    static ThemePack read(Reader input, boolean builtin) throws IOException {
        JsonReader reader = new JsonReader(input);
        reader.setLenient(false);
        JsonElement json = GSON.getAdapter(JsonElement.class).read(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw invalid("$", "unexpected content after the theme pack");
        }
        JsonObject root = object(json, "$");
        JsonElement version = root.get("version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || !"2".equals(version.getAsString())) {
            throw invalid("version", "expected version 2");
        }
        String defaultId = builtin || root.has("default") ? string(root.get("default"), "default") : "";
        JsonElement entries = root.get("themes");
        if (entries == null || !entries.isJsonArray()) {
            throw invalid("themes", "expected an array");
        }
        List<Definition> themes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement item : entries.getAsJsonArray()) {
            String path = "themes[" + themes.size() + "]";
            JsonObject theme = object(item, path);
            String id = string(theme.get("id"), path + ".id");
            if (!ids.add(id)) {
                throw invalid(path + ".id", "duplicate id " + id);
            }
            String name = string(theme.get("name"), path + ".name");
            JsonObject values = object(theme.get("colors"), path + ".colors");
            Map<String, Integer> colors = new HashMap<>();
            for (var entry : values.entrySet()) {
                String field = entry.getKey();
                String fieldPath = path + ".colors." + field;
                if (!FIELDS.contains(field)) {
                    throw invalid(fieldPath, "unknown color field");
                }
                String css = string(entry.getValue(), fieldPath);
                if (!CSS_COLOR.matcher(css).matches()) {
                    throw invalid(fieldPath, "expected #RRGGBB or #RRGGBBAA");
                }
                long digits = Long.parseLong(css.substring(1), 16);
                int argb = css.length() == 7 ? 0xff000000 | (int) digits : Integer.rotateRight((int) digits, 8);
                colors.put(field, argb);
            }
            if (builtin && !colors.keySet().equals(FIELDS)) {
                throw invalid(path + ".colors", "built-in theme is incomplete");
            }
            themes.add(new Definition(id, name, Map.copyOf(colors)));
        }
        if (builtin && !ids.contains(defaultId)) {
            throw invalid("default", "built-in default id is missing from themes");
        }
        return new ThemePack(defaultId, List.copyOf(themes));
    }

    private static JsonObject object(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw invalid(path, "expected an object");
        }
        return element.getAsJsonObject();
    }

    private static String string(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw invalid(path, "expected a nonempty string");
        }
        return element.getAsString();
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("[" + path + "] " + message);
    }
}
