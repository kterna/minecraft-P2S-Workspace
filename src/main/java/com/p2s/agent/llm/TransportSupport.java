package com.p2s.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Helpers shared by the protocol transports. */
final class TransportSupport {
    private TransportSupport() {
    }

    /** Name, description and JSON schema of a tool, taken from its OpenAI-style declaration. */
    record ToolSpec(String name, String description, JsonObject parameters) {
    }

    static List<ToolSpec> toolSpecs(List<AgentTool> tools) {
        List<ToolSpec> specs = new ArrayList<>();
        for (AgentTool tool : tools) {
            JsonObject declaration = tool.declaration();
            JsonObject function = Json.object(declaration, "function");
            if (function == null) {
                function = declaration;
            }
            String name = Json.string(function, "name");
            if (name.isBlank()) {
                name = tool.name();
            }
            JsonObject parameters = Json.object(function, "parameters");
            if (parameters == null) {
                parameters = new JsonObject();
                parameters.addProperty("type", "object");
                parameters.add("properties", new JsonObject());
            }
            specs.add(new ToolSpec(name, Json.string(function, "description"), parameters.deepCopy()));
        }
        return specs;
    }

    /** Arguments to echo back: never an incomplete object. */
    static JsonObject arguments(AgentMessage.ToolCall call) {
        return call.hasValidArguments() ? call.arguments() : new JsonObject();
    }

    static String argumentsText(AgentMessage.ToolCall call) {
        if (!call.hasValidArguments()) {
            return "{}";
        }
        String raw = call.rawArguments();
        return raw.isBlank() ? call.arguments().toString() : raw;
    }

    /** Provider replay data of {@code message} when it came from the model of {@code cfg}; otherwise null. */
    static JsonObject replay(AgentMessage.Assistant message, LlmRequestConfig cfg) {
        AgentMessage.Replay replay = message.replay();
        if (replay == null || !replay.matches(cfg.api().id(), cfg.provider(), cfg.model())) {
            return null;
        }
        return replay.data();
    }

    static AgentMessage.Replay origin(LlmRequestConfig cfg, JsonObject data) {
        return new AgentMessage.Replay(cfg.api().id(), cfg.provider(), cfg.model(), data);
    }

    /** Ids matching ^[a-zA-Z0-9_-]{1,64}$ (Anthropic, Bedrock, Gemini 3). Deterministic, so calls and results stay paired. */
    static String safeToolId(String id) {
        if (id == null || id.isEmpty()) return "call_" + shortHash("");
        if (id.length() <= 64 && id.matches("[a-zA-Z0-9_-]+")) return id;
        String sanitized = id.replaceAll("[^a-zA-Z0-9_-]", "_");
        return sanitized.substring(0, Math.min(39, sanitized.length())) + "_" + shortHash(id);
    }

    /** Resolves a base or full endpoint without modifying its existing query parameters. */
    static String endpoint(String raw, String route, boolean addVersion) {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.get(raw);
        String path = url.encodedPath().replaceFirst("/+$", "");
        if (!path.endsWith("/" + route)) {
            if (addVersion && !path.endsWith("/v1")) path += "/v1";
            path += "/" + route;
        }
        return url.newBuilder().encodedPath(path).build().toString();
    }

    /** Mistral wants exactly 9 alphanumeric characters. */
    static String mistralToolId(String id) {
        String alnum = id == null ? "" : id.replaceAll("[^a-zA-Z0-9]", "");
        if (alnum.length() == 9) {
            return alnum;
        }
        return shortHash(String.valueOf(id)).replaceAll("[^a-zA-Z0-9]", "").substring(0, 9);
    }

    static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Removes JSON-schema keys that a provider's schema dialect rejects, recursively. */
    static JsonElement stripKeys(JsonElement schema, java.util.Set<String> keys) {
        if (schema == null) {
            return null;
        }
        if (schema.isJsonArray()) {
            JsonArray out = new JsonArray();
            schema.getAsJsonArray().forEach(item -> out.add(stripKeys(item, keys)));
            return out;
        }
        if (!schema.isJsonObject()) {
            return schema.deepCopy();
        }
        JsonObject out = new JsonObject();
        for (var entry : schema.getAsJsonObject().entrySet()) {
            if (keys.contains(entry.getKey())) {
                continue;
            }
            out.add(entry.getKey(), stripKeys(entry.getValue(), keys));
        }
        return out;
    }

    /** Builder for the neutral reply shared by the stream parsers. */
    static final class Reply {
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final List<AgentMessage.ToolCall> calls = new ArrayList<>();
        AgentMessage.Usage usage = AgentMessage.Usage.NONE;
    }
}
