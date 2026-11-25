package com.p2s.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.core.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Accumulates streamed tool-call fragments per index and validates them at the end.
 * Incomplete argument JSON is never repaired: such calls are marked invalid so the loop
 * reports them to the model instead of executing a guess.
 */
public final class ToolCallAssembler {
    private final Map<Integer, Partial> partials = new TreeMap<>();

    /** Returns true when this delta opened a new call at {@code index}. */
    public boolean append(int index, String id, String name, String argumentsDelta) {
        boolean created = !partials.containsKey(index);
        Partial partial = partials.computeIfAbsent(index, ignored -> new Partial());
        if (id != null && !id.isEmpty()) {
            partial.id = id;
        }
        if (name != null && !name.isEmpty()) {
            partial.name = partial.name.isEmpty() ? name : partial.name;
        }
        if (argumentsDelta != null) {
            partial.arguments.append(argumentsDelta);
        }
        return created;
    }

    public boolean isEmpty() {
        return partials.isEmpty();
    }

    public String idAt(int index) {
        Partial partial = partials.get(index);
        return partial == null ? "" : partial.id;
    }

    public String nameAt(int index) {
        Partial partial = partials.get(index);
        return partial == null ? "" : partial.name;
    }

    /** Completed calls in index order. {@code truncated} marks every call invalid. */
    public List<AgentMessage.ToolCall> finish(boolean truncated) {
        List<AgentMessage.ToolCall> calls = new ArrayList<>();
        for (Map.Entry<Integer, Partial> entry : partials.entrySet()) {
            Partial partial = entry.getValue();
            String id = partial.id.isBlank() ? syntheticId(entry.getKey()) : partial.id;
            String raw = partial.arguments.toString();
            if (truncated) {
                calls.add(AgentMessage.ToolCall.invalid(id, partial.name, raw,
                        "the response was cut off by the output length limit"));
                continue;
            }
            calls.add(decodeText(id, partial.name, raw));
        }
        return calls;
    }

    /** Decode a complete (non-streamed) tool call whose arguments may be a string or an object. */
    public static AgentMessage.ToolCall decode(String id, String name, JsonElement arguments) {
        String safeId = id == null || id.isBlank() ? syntheticId(0) : id;
        if (arguments != null && arguments.isJsonObject()) {
            return AgentMessage.ToolCall.valid(safeId, name, arguments.toString(), arguments.getAsJsonObject());
        }
        String raw = arguments == null || arguments.isJsonNull() ? "" : OpenAiMessageCodec.contentText(arguments);
        return decodeText(safeId, name, raw);
    }

    static AgentMessage.ToolCall decodeText(String id, String name, String raw) {
        if (name == null || name.isBlank()) {
            return AgentMessage.ToolCall.invalid(id, "", raw, "missing function name");
        }
        if (raw == null || raw.isBlank()) {
            return AgentMessage.ToolCall.valid(id, name, "{}", new JsonObject());
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed.isJsonObject()) {
                return AgentMessage.ToolCall.valid(id, name, raw, parsed.getAsJsonObject());
            }
            if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
                // Some providers double-encode arguments as a JSON string.
                JsonElement inner = JsonParser.parseString(parsed.getAsString());
                if (inner.isJsonObject()) {
                    return AgentMessage.ToolCall.valid(id, name, parsed.getAsString(), inner.getAsJsonObject());
                }
            }
            return AgentMessage.ToolCall.invalid(id, name, raw, "arguments are not a JSON object");
        } catch (RuntimeException ex) {
            return AgentMessage.ToolCall.invalid(id, name, raw, "arguments are incomplete or malformed JSON");
        }
    }

    private static String syntheticId(int index) {
        return "p2s_call_" + index + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static final class Partial {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }
}
