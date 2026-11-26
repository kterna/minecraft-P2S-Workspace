package com.p2s.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.core.AgentMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the provider-neutral transcript to and from OpenAI Chat Completions messages.
 * The same JSON is the persisted session history, so sessions saved by the previous harness
 * load unchanged.
 */
public final class OpenAiMessageCodec {
    private static final Gson GSON = new Gson();

    private OpenAiMessageCodec() {
    }

    /** Version of the {@value #REPLAY_KEY} extension written on assistant messages. */
    static final int REPLAY_VERSION = 1;
    /** Extension field on persisted assistant messages; never sent to a provider. */
    public static final String REPLAY_KEY = "p2s_replay";

    /**
     * Persisted history: wire messages with an optional leading system message. Assistant
     * thinking is kept as {@code reasoning_content}, which older versions already read, and
     * provider replay data (signatures, encrypted reasoning) as the versioned
     * {@value #REPLAY_KEY} extension, which older versions ignore.
     */
    public static List<JsonObject> toWire(String systemPrompt, List<AgentMessage> messages) {
        List<JsonObject> wire = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            wire.add(system);
        }
        for (AgentMessage message : messages) {
            JsonObject encoded = encode(message);
            if (encoded == null) {
                continue;
            }
            if (message instanceof AgentMessage.Assistant assistant) {
                if (!assistant.thinking().isEmpty()) {
                    encoded.addProperty("reasoning_content", assistant.thinking());
                }
                if (assistant.replay() != null) {
                    JsonObject replay = new JsonObject();
                    replay.addProperty("v", REPLAY_VERSION);
                    replay.addProperty("api", assistant.replay().api());
                    replay.addProperty("provider", assistant.replay().provider());
                    replay.addProperty("model", assistant.replay().model());
                    replay.add("data", assistant.replay().data());
                    encoded.add(REPLAY_KEY, replay);
                }
            }
            wire.add(encoded);
        }
        return wire;
    }

    /** Copy of a persisted message without P2S extension fields. */
    public static JsonObject withoutExtensions(JsonObject message) {
        JsonObject copy = message.deepCopy();
        copy.remove(REPLAY_KEY);
        return copy;
    }

    public static JsonObject encode(AgentMessage message) {
        if (message instanceof AgentMessage.User user) {
            JsonObject json = new JsonObject();
            json.addProperty("role", "user");
            json.addProperty("content", user.content());
            return json;
        }
        if (message instanceof AgentMessage.Assistant assistant) {
            JsonObject json = new JsonObject();
            json.addProperty("role", "assistant");
            if (assistant.text().isEmpty()) {
                json.add("content", JsonNull.INSTANCE);
            } else {
                json.addProperty("content", assistant.text());
            }
            if (assistant.hasToolCalls()) {
                JsonArray calls = new JsonArray();
                for (AgentMessage.ToolCall call : assistant.toolCalls()) {
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    // Providers may parse echoed arguments; never send back an incomplete object.
                    function.addProperty("arguments", call.hasValidArguments()
                            ? normalizedArguments(call)
                            : "{}");
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("id", call.id());
                    encoded.addProperty("type", "function");
                    encoded.add("function", function);
                    calls.add(encoded);
                }
                json.add("tool_calls", calls);
            }
            return json;
        }
        if (message instanceof AgentMessage.ToolResult result) {
            JsonObject json = new JsonObject();
            json.addProperty("role", "tool");
            if (!result.toolCallId().isBlank()) {
                json.addProperty("tool_call_id", result.toolCallId());
            }
            json.addProperty("content", GSON.toJson(result.content()));
            return json;
        }
        return null;
    }

    private static String normalizedArguments(AgentMessage.ToolCall call) {
        String raw = call.rawArguments();
        return raw.isBlank() ? GSON.toJson(call.arguments()) : raw;
    }

    /** Parse persisted or legacy wire history. Leading system messages become the system prompt. */
    public static Decoded fromWire(List<JsonObject> wire) {
        StringBuilder system = new StringBuilder();
        List<AgentMessage> messages = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        if (wire == null) {
            return new Decoded("", messages);
        }
        boolean leading = true;
        for (JsonObject json : wire) {
            if (json == null) {
                continue;
            }
            String role = string(json, "role");
            if ("system".equals(role)) {
                if (leading) {
                    if (!system.isEmpty()) {
                        system.append("\n\n");
                    }
                    system.append(contentText(json.get("content")));
                } else {
                    // A system message in the middle has no neutral equivalent; keep it as context.
                    messages.add(new AgentMessage.User("[system] " + contentText(json.get("content")), 0L));
                }
                continue;
            }
            leading = false;
            switch (role) {
                case "user" -> messages.add(new AgentMessage.User(contentText(json.get("content")), 0L));
                case "assistant" -> messages.add(decodeAssistant(json, toolNames));
                case "tool" -> {
                    String id = string(json, "tool_call_id");
                    JsonObject content = parseToolContent(contentText(json.get("content")));
                    messages.add(new AgentMessage.ToolResult(id, toolNames.getOrDefault(id, string(json, "name")),
                            content, isErrorPayload(content), 0L));
                }
                default -> {
                    // Unknown roles are not sent to providers.
                }
            }
        }
        return new Decoded(system.toString(), messages);
    }

    private static AgentMessage.Assistant decodeAssistant(JsonObject json, Map<String, String> toolNames) {
        List<AgentMessage.ToolCall> calls = new ArrayList<>();
        if (json.has("tool_calls") && json.get("tool_calls").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("tool_calls")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject call = element.getAsJsonObject();
                JsonObject function = call.has("function") && call.get("function").isJsonObject()
                        ? call.getAsJsonObject("function") : new JsonObject();
                AgentMessage.ToolCall decoded = ToolCallAssembler.decode(string(call, "id"), string(function, "name"),
                        function.get("arguments"));
                calls.add(decoded);
                toolNames.put(decoded.id(), decoded.name());
            }
        } else if (json.has("function_call") && json.get("function_call").isJsonObject()) {
            JsonObject function = json.getAsJsonObject("function_call");
            calls.add(ToolCallAssembler.decode("", string(function, "name"), function.get("arguments")));
        }
        AgentMessage.StopReason reason = calls.isEmpty() ? AgentMessage.StopReason.STOP : AgentMessage.StopReason.TOOL_USE;
        String thinking = json.has("reasoning_content") ? contentText(json.get("reasoning_content")) : "";
        return new AgentMessage.Assistant(contentText(json.get("content")), thinking, calls, reason, "", 0L,
                decodeReplay(json), null);
    }

    /** Unknown versions are ignored: replaying data we do not understand could break the request. */
    private static AgentMessage.Replay decodeReplay(JsonObject json) {
        if (!json.has(REPLAY_KEY) || !json.get(REPLAY_KEY).isJsonObject()) {
            return null;
        }
        JsonObject replay = json.getAsJsonObject(REPLAY_KEY);
        try {
            if (!replay.has("v") || replay.get("v").getAsInt() != REPLAY_VERSION) {
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        JsonObject data = replay.has("data") && replay.get("data").isJsonObject() ? replay.getAsJsonObject("data") : null;
        return new AgentMessage.Replay(string(replay, "api"), string(replay, "provider"), string(replay, "model"), data);
    }

    static JsonObject parseToolContent(String text) {
        if (text != null && !text.isBlank()) {
            try {
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (RuntimeException ignored) {
                // Fall through: keep non-JSON results as text.
            }
        }
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("text", text == null ? "" : text);
        return wrapped;
    }

    private static boolean isErrorPayload(JsonObject payload) {
        try {
            return payload.has("ok") && !payload.get("ok").getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Text of a string content or of the text parts of a content array. */
    static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    text.append(contentText(part.getAsJsonObject().get("text")));
                } else if (part.isJsonPrimitive()) {
                    text.append(part.getAsString());
                }
            }
            return text.toString();
        }
        return content.toString();
    }

    static String string(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    public record Decoded(String systemPrompt, List<AgentMessage> messages) {
        public Decoded {
            messages = List.copyOf(messages);
        }
    }
}
