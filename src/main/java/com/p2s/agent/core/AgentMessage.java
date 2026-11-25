package com.p2s.agent.core;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * Transcript entries owned by the agent (pi: AgentMessage). They are provider-neutral;
 * the transport converts them to a wire format only at the request boundary.
 */
public sealed interface AgentMessage permits AgentMessage.User, AgentMessage.Assistant, AgentMessage.ToolResult {

    long timestamp();

    record User(String content, long timestamp) implements AgentMessage {
        public User {
            content = content == null ? "" : content;
        }

        public static User of(String content) {
            return new User(content, System.currentTimeMillis());
        }
    }

    record Assistant(
            String text,
            String thinking,
            List<ToolCall> toolCalls,
            StopReason stopReason,
            String errorMessage,
            long timestamp
    ) implements AgentMessage {
        public Assistant {
            text = text == null ? "" : text;
            thinking = thinking == null ? "" : thinking;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            stopReason = stopReason == null ? StopReason.STOP : stopReason;
            errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public static Assistant failure(StopReason reason, String message) {
            return new Assistant("", "", List.of(), reason, message, System.currentTimeMillis());
        }

        public boolean failed() {
            return stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED;
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    record ToolResult(String toolCallId, String toolName, JsonObject content, boolean isError, long timestamp)
            implements AgentMessage {
        public ToolResult {
            toolCallId = toolCallId == null ? "" : toolCallId;
            toolName = toolName == null ? "" : toolName;
            content = content == null ? new JsonObject() : content.deepCopy();
        }

        @Override
        public JsonObject content() {
            return content.deepCopy();
        }
    }

    /** Why a provider response ended. ERROR and ABORTED responses never execute tools. */
    enum StopReason {
        STOP,
        TOOL_USE,
        LENGTH,
        ERROR,
        ABORTED
    }

    /**
     * A tool call as requested by the model. {@code arguments} is null when the raw
     * argument text was not a complete JSON object; such calls are reported back to the
     * model as errors and are never executed.
     */
    record ToolCall(String id, String name, String rawArguments, JsonObject arguments, String argumentError) {
        public ToolCall {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            rawArguments = rawArguments == null ? "" : rawArguments;
            arguments = arguments == null ? null : arguments.deepCopy();
            argumentError = argumentError == null ? "" : argumentError;
        }

        public static ToolCall valid(String id, String name, String rawArguments, JsonObject arguments) {
            return new ToolCall(id, name, rawArguments, arguments, "");
        }

        public static ToolCall invalid(String id, String name, String rawArguments, String error) {
            return new ToolCall(id, name, rawArguments, null, error);
        }

        @Override
        public JsonObject arguments() {
            return arguments == null ? null : arguments.deepCopy();
        }

        public boolean hasValidArguments() {
            return arguments != null && argumentError.isEmpty();
        }
    }
}
