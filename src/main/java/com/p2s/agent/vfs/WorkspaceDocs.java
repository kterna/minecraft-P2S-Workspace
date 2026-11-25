package com.p2s.agent.vfs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.WorkspaceTomlCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Workspace TOML as a JSON object model, for {@code set_part} and the script {@code ws} API:
 * {@code {workspace:{name,type,areaTag,origin,size}, palette:{}, parts:[{name,priority,actions:[...]}]}}.
 * Parsing and printing go through {@link WorkspaceTomlCodec}, so there is one format.
 */
public final class WorkspaceDocs {
    private static final Gson GSON = new GsonBuilder().create();

    private WorkspaceDocs() {
    }

    public static JsonObject parse(String toml) throws VfsException {
        try {
            WorkspaceTomlCodec.WorkspaceTomlDocument document = WorkspaceTomlCodec.parse(toml == null ? "" : toml);
            JsonObject json = GSON.toJsonTree(document).getAsJsonObject();
            json.remove("formatVersion");
            stripEmptyActionFields(json);
            return json;
        } catch (RuntimeException ex) {
            throw new VfsException("Invalid workspace TOML: " + message(ex));
        }
    }

    public static String print(JsonObject document) throws VfsException {
        try {
            WorkspaceTomlCodec.WorkspaceTomlDocument parsed = GSON.fromJson(document, WorkspaceTomlCodec.WorkspaceTomlDocument.class);
            if (parsed == null) {
                throw new VfsException("Workspace document is empty");
            }
            parsed.formatVersion = WorkspaceTomlCodec.FORMAT_VERSION;
            if (parsed.workspace == null) {
                parsed.workspace = new WorkspaceTomlCodec.WorkspaceHeader();
            }
            if (parsed.parts == null) {
                parsed.parts = new ArrayList<>();
            }
            String text = WorkspaceTomlCodec.write(parsed);
            // Round-trip through the parser so invalid documents fail here, not on the server.
            WorkspaceTomlCodec.parse(text);
            return text;
        } catch (VfsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new VfsException("Invalid workspace document: " + message(ex));
        }
    }

    /** Throws when the text is not a valid workspace file. */
    public static void validate(String toml) throws VfsException {
        parse(toml);
    }

    /** Replaces or appends the actions of one part, creating the part when missing. */
    public static String setPart(String toml, String partName, JsonArray actions, boolean append, Integer priority)
            throws VfsException {
        if (partName == null || partName.isBlank()) {
            throw new VfsException("part is required");
        }
        JsonObject document = parse(toml);
        JsonArray parts = document.has("parts") && document.get("parts").isJsonArray()
                ? document.getAsJsonArray("parts") : new JsonArray();
        JsonObject target = null;
        for (JsonElement element : parts) {
            if (element.isJsonObject() && partName.equals(stringOf(element.getAsJsonObject(), "name"))) {
                target = element.getAsJsonObject();
                break;
            }
        }
        if (target == null) {
            target = new JsonObject();
            target.addProperty("name", partName);
            target.addProperty("priority", priority == null ? 0 : priority);
            target.add("actions", new JsonArray());
            parts.add(target);
        } else if (priority != null) {
            target.addProperty("priority", priority);
        }
        JsonArray existing = append && target.has("actions") && target.get("actions").isJsonArray()
                ? target.getAsJsonArray("actions") : new JsonArray();
        for (JsonElement action : actions) {
            existing.add(action.deepCopy());
        }
        target.add("actions", existing);
        document.add("parts", parts);
        return print(document);
    }

    public static List<String> partNames(JsonObject document) {
        List<String> names = new ArrayList<>();
        if (document.has("parts") && document.get("parts").isJsonArray()) {
            for (JsonElement element : document.getAsJsonArray("parts")) {
                if (element.isJsonObject()) {
                    names.add(stringOf(element.getAsJsonObject(), "name"));
                }
            }
        }
        return names;
    }

    private static void stripEmptyActionFields(JsonObject document) {
        if (!document.has("parts") || !document.get("parts").isJsonArray()) {
            return;
        }
        for (JsonElement part : document.getAsJsonArray("parts")) {
            if (!part.isJsonObject() || !part.getAsJsonObject().has("actions")) {
                continue;
            }
            for (JsonElement action : part.getAsJsonObject().getAsJsonArray("actions")) {
                if (!action.isJsonObject()) {
                    continue;
                }
                JsonObject object = action.getAsJsonObject();
                for (String key : List.of("mode", "axis", "facing")) {
                    if (object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsString().isEmpty()) {
                        object.remove(key);
                    }
                }
            }
        }
    }

    private static String stringOf(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String message(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
