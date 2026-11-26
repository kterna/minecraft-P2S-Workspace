package com.p2s.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Lenient accessors for provider JSON, which is often incomplete or loosely typed. */
final class Json {
    private Json() {
    }

    static String string(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            JsonElement value = json.get(key);
            return value.isJsonPrimitive() ? value.getAsString() : value.toString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    static JsonObject object(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
    }

    static JsonArray array(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    static long number(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return json.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    static boolean bool(JsonObject json, String key) {
        try {
            return json != null && json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static JsonObject parseObject(String text) {
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
