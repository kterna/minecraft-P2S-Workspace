package com.p2s.agent.core;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * Lifecycle events emitted by the loop (pi: AgentEvent). Listeners run synchronously on the
 * loop thread, in order. {@link AgentEnd} is always the last event of a run.
 */
public sealed interface AgentEvent {
    record AgentStart() implements AgentEvent {
    }

    record AgentEnd(List<AgentMessage> newMessages, EndReason reason) implements AgentEvent {
        public AgentEnd {
            newMessages = List.copyOf(newMessages);
        }
    }

    record TurnStart() implements AgentEvent {
    }

    record TurnEnd(AgentMessage.Assistant message, List<AgentMessage.ToolResult> toolResults) implements AgentEvent {
        public TurnEnd {
            toolResults = List.copyOf(toolResults);
        }
    }

    record MessageStart(AgentMessage message) implements AgentEvent {
    }

    record MessageUpdate(AssistantMessageEvent event) implements AgentEvent {
    }

    record MessageEnd(AgentMessage message) implements AgentEvent {
    }

    record ToolExecutionStart(String toolCallId, String toolName, JsonObject arguments) implements AgentEvent {
        public ToolExecutionStart {
            arguments = arguments == null ? null : arguments.deepCopy();
        }
    }

    record ToolExecutionEnd(AgentMessage.ToolCall toolCall, AgentMessage.ToolResult result) implements AgentEvent {
    }

    /** Why a run stopped. PAUSED means a tool is waiting for the user (approval or choice). */
    enum EndReason {
        COMPLETED,
        PAUSED,
        STOPPED_BY_POLICY,
        ERROR,
        ABORTED
    }
}
