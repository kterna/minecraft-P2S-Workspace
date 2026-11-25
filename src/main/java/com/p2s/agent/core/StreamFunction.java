package com.p2s.agent.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * Model transport used by the loop (pi: StreamFn).
 *
 * <p>Contract: must not throw for request, network or protocol failures. Failures are
 * encoded in the returned message with {@link AgentMessage.StopReason#ERROR} or
 * {@link AgentMessage.StopReason#ABORTED} and an error message. Incremental output goes to
 * {@code sink} on the calling thread before the method returns.
 */
@FunctionalInterface
public interface StreamFunction {
    AgentMessage.Assistant stream(Context context, AbortSignal signal, Consumer<AssistantMessageEvent> sink);

    record Context(String systemPrompt, List<AgentMessage> messages, List<AgentTool> tools) {
        public Context {
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }
}
