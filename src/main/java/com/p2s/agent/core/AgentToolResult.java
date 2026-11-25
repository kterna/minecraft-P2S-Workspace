package com.p2s.agent.core;

import com.google.gson.JsonObject;

/**
 * Result of one tool execution.
 *
 * <p>{@link Control#PAUSE} means the tool handed control to the user (an approval or a
 * choice). Remaining calls of the batch are closed with "not executed" results and the run
 * ends with {@link AgentEvent.EndReason#PAUSED}; the user's answer starts the next run.
 *
 * <p>{@link Control#TERMINATE} keeps pi semantics: the run stops after the batch only when
 * every result in the batch asks for it.
 */
public record AgentToolResult(JsonObject content, boolean isError, Control control) {
    public AgentToolResult {
        content = content == null ? new JsonObject() : content.deepCopy();
        control = control == null ? Control.NONE : control;
    }

    @Override
    public JsonObject content() {
        return content.deepCopy();
    }

    public static AgentToolResult of(JsonObject content) {
        return new AgentToolResult(content, isErrorPayload(content), Control.NONE);
    }

    public static AgentToolResult error(String toolName, String code, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        payload.addProperty("error", message == null ? "" : message);
        if (code != null && !code.isBlank()) {
            payload.addProperty("error_code", code);
        }
        return new AgentToolResult(payload, true, Control.NONE);
    }

    public AgentToolResult withControl(Control next) {
        return new AgentToolResult(content, isError, next);
    }

    /** Tools in this project report failures as {@code {"ok": false, ...}}. */
    public static boolean isErrorPayload(JsonObject payload) {
        if (payload == null) {
            return true;
        }
        try {
            return payload.has("ok") && !payload.get("ok").getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public enum Control {
        NONE,
        PAUSE,
        TERMINATE
    }
}
