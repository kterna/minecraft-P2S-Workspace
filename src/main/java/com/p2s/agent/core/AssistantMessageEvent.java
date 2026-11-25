package com.p2s.agent.core;

/** Incremental provider output (pi: AssistantMessageEvent). Final state arrives as an Assistant message. */
public sealed interface AssistantMessageEvent {
    record TextDelta(String delta) implements AssistantMessageEvent {
    }

    record ThinkingDelta(String delta) implements AssistantMessageEvent {
    }

    record ToolCallStart(int index, String id, String name) implements AssistantMessageEvent {
    }

    record ToolCallDelta(int index, String argumentsDelta) implements AssistantMessageEvent {
    }
}
