package com.p2s.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.llm.OpenAiMessageCodec;

import java.util.ArrayList;
import java.util.List;

/** Keeps the legacy wire entries readable while retaining local details that providers omit. */
public final class SessionTranscript {
    private static final Gson GSON = new Gson();

    private SessionTranscript() {}

    public static List<JsonObject> encode(String system, List<AgentMessage> raw) {
        List<JsonObject> out = OpenAiMessageCodec.toWire(system, raw);
        int offset = out.size() - raw.size();
        for (int i = 0; i < raw.size(); i++) {
            AgentMessage message = raw.get(i);
            JsonObject json = out.get(i + offset);
            if (message.timestamp() != 0) {
                json.addProperty("p2sTimestamp", message.timestamp());
            }
            if (message instanceof AgentMessage.Assistant a) {
                if (a.failed() || a.toolCalls().stream().anyMatch(call -> !call.hasValidArguments())) {
                    json.add("p2sRawAssistant", GSON.toJsonTree(a));
                } else {
                    json.addProperty("p2sStopReason", a.stopReason().name());
                    if (!a.thinking().isEmpty()) {
                        json.addProperty("reasoning_content", a.thinking());
                    }
                }
            } else if (message instanceof AgentMessage.ToolResult tool) {
                json.addProperty("p2sToolName", tool.toolName());
                json.addProperty("p2sToolError", tool.isError());
            }
        }
        return out;
    }

    public static List<AgentMessage> decode(List<JsonObject> history) {
        var decoded = OpenAiMessageCodec.fromWire(history).messages();
        if (history == null || history.isEmpty()) {
            return decoded;
        }
        int offset = history.size() - decoded.size();
        if (offset < 0 || offset > 1 || (offset == 1 && (history.getFirst() == null
                || !new com.google.gson.JsonPrimitive("system").equals(history.getFirst().get("role"))))) {
            return decoded; // Legacy history with unknown entries follows its original decoder.
        }
        List<AgentMessage> out = new ArrayList<>(decoded.size());
        for (int i = 0; i < decoded.size(); i++) {
            JsonObject entry = history.get(i + offset);
            AgentMessage message = decoded.get(i);
            try {
                if (entry == null) {
                    out.add(message);
                    continue;
                }
                long timestamp = entry.has("p2sTimestamp") ? entry.get("p2sTimestamp").getAsLong() : message.timestamp();
                if (message instanceof AgentMessage.User user) {
                    message = new AgentMessage.User(user.content(), timestamp);
                } else if (message instanceof AgentMessage.Assistant a) {
                    if (entry.has("p2sRawAssistant")) {
                        message = GSON.fromJson(entry.get("p2sRawAssistant"), AgentMessage.Assistant.class);
                    } else if (entry.has("p2sStopReason")) {
                        // Apply local fields to the decoded record without dropping provider-specific replay data.
                        JsonObject record = GSON.toJsonTree(a).getAsJsonObject();
                        String stopReason = AgentMessage.StopReason.valueOf(entry.get("p2sStopReason").getAsString()).name();
                        record.addProperty("stopReason", stopReason);
                        record.addProperty("timestamp", timestamp);
                        message = GSON.fromJson(record, AgentMessage.Assistant.class);
                    }
                } else if (message instanceof AgentMessage.ToolResult t && entry.has("p2sToolName")) {
                    message = new AgentMessage.ToolResult(t.toolCallId(), entry.get("p2sToolName").getAsString(),
                            t.content(), entry.get("p2sToolError").getAsBoolean(), timestamp);
                }
            } catch (RuntimeException malformed) {
                // Damaged optional metadata must not prevent the rest of an older save loading.
            }
            out.add(message);
        }
        return out;
    }
}
