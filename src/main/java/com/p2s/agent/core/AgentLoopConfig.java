package com.p2s.agent.core;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Hooks and collaborators of one loop invocation (pi: AgentLoopConfig). All hooks run on
 * the loop thread and must not throw; a throwing hook fails the run.
 */
public record AgentLoopConfig(
        StreamFunction streamFunction,
        Function<List<AgentMessage>, List<AgentMessage>> transformContext,
        Function<BeforeToolCall, BeforeToolCallResult> beforeToolCall,
        Function<AfterToolCall, AgentToolResult> afterToolCall,
        Predicate<TurnContext> shouldStopAfterTurn,
        Function<TurnContext, List<AgentMessage>> prepareNextTurn,
        Supplier<List<AgentMessage>> steeringMessages,
        Supplier<List<AgentMessage>> followUpMessages,
        Executor toolExecutor,
        long abortGraceMillis
) {
    public static Builder builder(StreamFunction streamFunction) {
        return new Builder(streamFunction);
    }

    /** Context passed before a validated call executes. */
    public record BeforeToolCall(AgentMessage.Assistant assistant, AgentMessage.ToolCall toolCall,
                                 List<AgentMessage> messages) {
    }

    /** Return {@code block=true} to skip execution; the reason becomes the error result. */
    public record BeforeToolCallResult(boolean block, String reason) {
        public static BeforeToolCallResult blocked(String reason) {
            return new BeforeToolCallResult(true, reason);
        }
    }

    /** Context passed after execution; return a replacement result or null to keep it. */
    public record AfterToolCall(AgentMessage.Assistant assistant, AgentMessage.ToolCall toolCall,
                                AgentToolResult result, List<AgentMessage> messages) {
    }

    /** State after a turn's assistant message and tool results were appended. */
    public record TurnContext(AgentMessage.Assistant message, List<AgentMessage.ToolResult> toolResults,
                              List<AgentMessage> messages, List<AgentMessage> newMessages, int turnIndex) {
    }

    public static final class Builder {
        private final StreamFunction streamFunction;
        private Function<List<AgentMessage>, List<AgentMessage>> transformContext;
        private Function<BeforeToolCall, BeforeToolCallResult> beforeToolCall;
        private Function<AfterToolCall, AgentToolResult> afterToolCall;
        private Predicate<TurnContext> shouldStopAfterTurn;
        private Function<TurnContext, List<AgentMessage>> prepareNextTurn;
        private Supplier<List<AgentMessage>> steeringMessages;
        private Supplier<List<AgentMessage>> followUpMessages;
        private Executor toolExecutor;
        private long abortGraceMillis = 1500L;

        private Builder(StreamFunction streamFunction) {
            this.streamFunction = streamFunction;
        }

        public Builder transformContext(Function<List<AgentMessage>, List<AgentMessage>> value) {
            transformContext = value;
            return this;
        }

        public Builder beforeToolCall(Function<BeforeToolCall, BeforeToolCallResult> value) {
            beforeToolCall = value;
            return this;
        }

        public Builder afterToolCall(Function<AfterToolCall, AgentToolResult> value) {
            afterToolCall = value;
            return this;
        }

        public Builder shouldStopAfterTurn(Predicate<TurnContext> value) {
            shouldStopAfterTurn = value;
            return this;
        }

        public Builder prepareNextTurn(Function<TurnContext, List<AgentMessage>> value) {
            prepareNextTurn = value;
            return this;
        }

        public Builder steeringMessages(Supplier<List<AgentMessage>> value) {
            steeringMessages = value;
            return this;
        }

        public Builder followUpMessages(Supplier<List<AgentMessage>> value) {
            followUpMessages = value;
            return this;
        }

        public Builder toolExecutor(Executor value) {
            toolExecutor = value;
            return this;
        }

        public Builder abortGraceMillis(long value) {
            abortGraceMillis = Math.max(0L, value);
            return this;
        }

        public AgentLoopConfig build() {
            if (streamFunction == null) {
                throw new IllegalArgumentException("streamFunction is required");
            }
            return new AgentLoopConfig(streamFunction, transformContext, beforeToolCall, afterToolCall,
                    shouldStopAfterTurn, prepareNextTurn, steeringMessages, followUpMessages, toolExecutor,
                    abortGraceMillis);
        }
    }
}
