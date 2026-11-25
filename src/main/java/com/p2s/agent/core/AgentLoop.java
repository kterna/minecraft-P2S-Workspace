package com.p2s.agent.core;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentEvent.EndReason;
import com.p2s.agent.core.AgentMessage.Assistant;
import com.p2s.agent.core.AgentMessage.ToolCall;
import com.p2s.agent.core.AgentMessage.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Stateless agent loop (port of pi-agent-core {@code runLoop}). Runs entirely on the calling
 * thread; only adjacent PARALLEL tool calls are handed to the configured executor.
 *
 * <p>Invariants: every tool call of an assistant message receives exactly one result, in the
 * assistant's order, before the next provider request; ERROR/ABORTED responses never execute
 * tools; {@link AgentEvent.AgentEnd} is always the final event.
 */
public final class AgentLoop {
    public static final String NOT_EXECUTED = "not_executed";

    private AgentLoop() {
    }

    /** Start a run with new prompt messages appended to the context. */
    public static Outcome run(List<AgentMessage> prompts, StreamFunction.Context context, AgentLoopConfig config,
                              Consumer<AgentEvent> emit, AbortSignal signal) {
        List<AgentMessage> messages = new ArrayList<>(context.messages());
        List<AgentMessage> newMessages = new ArrayList<>();
        emit.accept(new AgentEvent.AgentStart());
        emit.accept(new AgentEvent.TurnStart());
        for (AgentMessage prompt : prompts) {
            emit.accept(new AgentEvent.MessageStart(prompt));
            emit.accept(new AgentEvent.MessageEnd(prompt));
            messages.add(prompt);
            newMessages.add(prompt);
        }
        return runLoop(context, messages, newMessages, config, emit, signal);
    }

    /** Continue from the existing context; the last message must not be an assistant message. */
    public static Outcome runContinue(StreamFunction.Context context, AgentLoopConfig config,
                                      Consumer<AgentEvent> emit, AbortSignal signal) {
        List<AgentMessage> messages = new ArrayList<>(context.messages());
        if (messages.isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }
        if (messages.getLast() instanceof Assistant) {
            throw new IllegalStateException("Cannot continue from an assistant message");
        }
        emit.accept(new AgentEvent.AgentStart());
        emit.accept(new AgentEvent.TurnStart());
        return runLoop(context, messages, new ArrayList<>(), config, emit, signal);
    }

    private static Outcome runLoop(StreamFunction.Context context, List<AgentMessage> messages,
                                   List<AgentMessage> newMessages, AgentLoopConfig config,
                                   Consumer<AgentEvent> emit, AbortSignal signal) {
        boolean firstTurn = true;
        int turnIndex = 0;
        List<AgentMessage> pending = poll(config.steeringMessages());
        while (true) {
            boolean hasMoreToolCalls = true;
            while (hasMoreToolCalls || !pending.isEmpty()) {
                if (!firstTurn) {
                    emit.accept(new AgentEvent.TurnStart());
                } else {
                    firstTurn = false;
                }
                for (AgentMessage message : pending) {
                    emit.accept(new AgentEvent.MessageStart(message));
                    emit.accept(new AgentEvent.MessageEnd(message));
                    messages.add(message);
                    newMessages.add(message);
                }
                pending = List.of();

                Assistant assistant = streamAssistant(context, messages, config, emit, signal);
                messages.add(assistant);
                newMessages.add(assistant);
                if (assistant.failed()) {
                    emit.accept(new AgentEvent.TurnEnd(assistant, List.of()));
                    return end(newMessages, assistant.stopReason() == AgentMessage.StopReason.ABORTED
                            ? EndReason.ABORTED : EndReason.ERROR, emit);
                }

                List<ToolResult> results = List.of();
                boolean paused = false;
                hasMoreToolCalls = false;
                if (assistant.hasToolCalls()) {
                    Batch batch = executeToolCalls(assistant, messages, context.tools(), config, emit, signal);
                    results = batch.results();
                    paused = batch.paused();
                    hasMoreToolCalls = !batch.terminate() && !paused;
                    messages.addAll(results);
                    newMessages.addAll(results);
                }
                emit.accept(new AgentEvent.TurnEnd(assistant, results));

                if (signal.isAborted()) {
                    return end(newMessages, EndReason.ABORTED, emit);
                }
                if (paused) {
                    return end(newMessages, EndReason.PAUSED, emit);
                }
                AgentLoopConfig.TurnContext turn = new AgentLoopConfig.TurnContext(assistant, results,
                        List.copyOf(messages), List.copyOf(newMessages), turnIndex++);
                if (config.prepareNextTurn() != null) {
                    List<AgentMessage> replacement = config.prepareNextTurn().apply(turn);
                    if (replacement != null) {
                        messages.clear();
                        messages.addAll(replacement);
                    }
                }
                if (config.shouldStopAfterTurn() != null && config.shouldStopAfterTurn().test(turn)) {
                    return end(newMessages, EndReason.STOPPED_BY_POLICY, emit);
                }
                pending = poll(config.steeringMessages());
            }
            List<AgentMessage> followUps = poll(config.followUpMessages());
            if (followUps.isEmpty()) {
                break;
            }
            pending = followUps;
        }
        return end(newMessages, EndReason.COMPLETED, emit);
    }

    private static Outcome end(List<AgentMessage> newMessages, EndReason reason, Consumer<AgentEvent> emit) {
        emit.accept(new AgentEvent.AgentEnd(newMessages, reason));
        return new Outcome(List.copyOf(newMessages), reason);
    }

    private static List<AgentMessage> poll(java.util.function.Supplier<List<AgentMessage>> source) {
        if (source == null) {
            return List.of();
        }
        List<AgentMessage> drained = source.get();
        return drained == null ? List.of() : List.copyOf(drained);
    }

    private static Assistant streamAssistant(StreamFunction.Context context, List<AgentMessage> messages,
                                             AgentLoopConfig config, Consumer<AgentEvent> emit,
                                             AbortSignal signal) {
        if (signal.isAborted()) {
            Assistant aborted = Assistant.failure(AgentMessage.StopReason.ABORTED, "Operation aborted");
            emit.accept(new AgentEvent.MessageStart(aborted));
            emit.accept(new AgentEvent.MessageEnd(aborted));
            return aborted;
        }
        List<AgentMessage> view = messages;
        if (config.transformContext() != null) {
            List<AgentMessage> transformed = config.transformContext().apply(List.copyOf(messages));
            if (transformed != null) {
                view = transformed;
            }
        }
        StreamFunction.Context request = new StreamFunction.Context(context.systemPrompt(), view, context.tools());
        emit.accept(new AgentEvent.MessageStart(new Assistant("", "", List.of(), AgentMessage.StopReason.STOP, "",
                System.currentTimeMillis())));
        Assistant result;
        try {
            result = config.streamFunction().stream(request, signal,
                    event -> emit.accept(new AgentEvent.MessageUpdate(event)));
        } catch (RuntimeException ex) {
            // Contract breach by the transport: encode it instead of breaking the event sequence.
            result = Assistant.failure(AgentMessage.StopReason.ERROR, describe(ex));
        }
        if (result == null) {
            result = Assistant.failure(AgentMessage.StopReason.ERROR, "Provider returned no message");
        }
        if (signal.isAborted() && result.failed() && result.stopReason() != AgentMessage.StopReason.ABORTED) {
            result = new Assistant(result.text(), result.thinking(), List.of(), AgentMessage.StopReason.ABORTED,
                    "Operation aborted", result.timestamp());
        }
        emit.accept(new AgentEvent.MessageEnd(result));
        return result;
    }

    private static Batch executeToolCalls(Assistant assistant, List<AgentMessage> messages, List<AgentTool> tools,
                                          AgentLoopConfig config, Consumer<AgentEvent> emit, AbortSignal signal) {
        List<ToolCall> calls = assistant.toolCalls();
        List<Finalized> finalized = new ArrayList<>();
        boolean paused = false;
        int index = 0;
        while (index < calls.size()) {
            if (paused || signal.isAborted()) {
                String reason = paused
                        ? "waiting for the user to respond to an earlier call in this batch"
                        : "the run was stopped";
                for (int i = index; i < calls.size(); i++) {
                    ToolCall call = calls.get(i);
                    emit.accept(new AgentEvent.ToolExecutionStart(call.id(), call.name(), call.arguments()));
                    Finalized skipped = new Finalized(call, notExecuted(call, reason));
                    emit.accept(new AgentEvent.ToolExecutionEnd(call, skipped.toMessage()));
                    finalized.add(skipped);
                }
                break;
            }
            int end = index + 1;
            if (isParallel(calls.get(index), tools)) {
                while (end < calls.size() && isParallel(calls.get(end), tools)) {
                    end++;
                }
            }
            List<ToolCall> group = calls.subList(index, end);
            List<Finalized> groupResults = executeGroup(assistant, group, messages, tools, config, emit, signal);
            for (Finalized result : groupResults) {
                finalized.add(result);
                if (result.result().control() == AgentToolResult.Control.PAUSE) {
                    paused = true;
                }
            }
            index = end;
        }

        List<ToolResult> results = new ArrayList<>();
        for (Finalized result : finalized) {
            ToolResult message = result.toMessage();
            emit.accept(new AgentEvent.MessageStart(message));
            emit.accept(new AgentEvent.MessageEnd(message));
            results.add(message);
        }
        boolean terminate = !finalized.isEmpty() && finalized.stream()
                .allMatch(result -> result.result().control() == AgentToolResult.Control.TERMINATE);
        return new Batch(List.copyOf(results), paused, terminate);
    }

    private static List<Finalized> executeGroup(Assistant assistant, List<ToolCall> group, List<AgentMessage> messages,
                                                List<AgentTool> tools, AgentLoopConfig config,
                                                Consumer<AgentEvent> emit, AbortSignal signal) {
        List<Object> prepared = new ArrayList<>();
        for (ToolCall call : group) {
            emit.accept(new AgentEvent.ToolExecutionStart(call.id(), call.name(), call.arguments()));
            prepared.add(prepare(assistant, call, messages, tools, config, signal));
        }

        boolean concurrent = group.size() > 1 && config.toolExecutor() != null;
        List<CompletableFuture<AgentToolResult>> running = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            Object item = prepared.get(i);
            if (item instanceof AgentToolResult immediate) {
                running.add(CompletableFuture.completedFuture(immediate));
                continue;
            }
            Prepared ready = (Prepared) item;
            if (!concurrent) {
                running.add(null);
                continue;
            }
            try {
                running.add(CompletableFuture.supplyAsync(() -> invoke(ready, signal), config.toolExecutor()));
            } catch (RejectedExecutionException ex) {
                running.add(CompletableFuture.completedFuture(
                        AgentToolResult.error(ready.call().name(), "executor_busy", "Tool execution queue is full")));
            }
        }

        List<Finalized> results = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            ToolCall call = group.get(i);
            Object item = prepared.get(i);
            AgentToolResult result;
            if (running.get(i) == null) {
                result = invoke((Prepared) item, signal);
            } else {
                result = await(running.get(i), call, signal, config.abortGraceMillis());
            }
            if (item instanceof Prepared && config.afterToolCall() != null) {
                AgentToolResult replaced = config.afterToolCall().apply(
                        new AgentLoopConfig.AfterToolCall(assistant, call, result, List.copyOf(messages)));
                if (replaced != null) {
                    result = replaced;
                }
            }
            Finalized done = new Finalized(call, result);
            emit.accept(new AgentEvent.ToolExecutionEnd(call, done.toMessage()));
            results.add(done);
        }
        return results;
    }

    private static Object prepare(Assistant assistant, ToolCall call, List<AgentMessage> messages,
                                  List<AgentTool> tools, AgentLoopConfig config, AbortSignal signal) {
        AgentTool tool = findTool(tools, call.name());
        if (tool == null) {
            return AgentToolResult.error(call.name(), "unknown_tool", "Tool " + call.name() + " not found");
        }
        if (!call.hasValidArguments()) {
            String detail = call.argumentError().isBlank() ? "arguments are not a JSON object" : call.argumentError();
            return AgentToolResult.error(call.name(), "invalid_arguments",
                    "Invalid tool arguments (" + detail + "); the call was not executed. Resend the complete call.");
        }
        if (signal.isAborted()) {
            return notExecuted(call, "the run was stopped");
        }
        if (config.beforeToolCall() != null) {
            AgentLoopConfig.BeforeToolCallResult decision = config.beforeToolCall().apply(
                    new AgentLoopConfig.BeforeToolCall(assistant, call, List.copyOf(messages)));
            if (decision != null && decision.block()) {
                String reason = decision.reason() == null || decision.reason().isBlank()
                        ? "Tool execution was blocked" : decision.reason();
                return AgentToolResult.error(call.name(), "blocked", reason);
            }
        }
        return new Prepared(call, tool);
    }

    private static AgentToolResult invoke(Prepared prepared, AbortSignal signal) {
        if (signal.isAborted()) {
            return notExecuted(prepared.call(), "the run was stopped");
        }
        try {
            AgentToolResult result = prepared.tool().execute(prepared.call().id(), prepared.call().arguments(), signal);
            return result == null
                    ? AgentToolResult.error(prepared.call().name(), "empty_result", "Tool returned no result")
                    : result;
        } catch (AbortSignal.AbortedException ex) {
            return AgentToolResult.error(prepared.call().name(), "aborted", "Tool was cancelled: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AgentToolResult.error(prepared.call().name(), "interrupted", "Tool execution was interrupted");
        } catch (Exception ex) {
            return AgentToolResult.error(prepared.call().name(), "execution_failed", describe(ex));
        }
    }

    /**
     * Waits for a concurrent call. After abort, tools get a short grace period to observe the
     * signal; a call that is still running then is reported as aborted and its late result is
     * discarded, so Stop never waits on a stuck tool.
     */
    private static AgentToolResult await(CompletableFuture<AgentToolResult> future, ToolCall call,
                                         AbortSignal signal, long graceMillis) {
        long abortedAt = -1L;
        while (true) {
            try {
                return future.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                if (signal.isAborted()) {
                    long now = System.nanoTime();
                    if (abortedAt < 0) {
                        abortedAt = now;
                    } else if (TimeUnit.NANOSECONDS.toMillis(now - abortedAt) >= graceMillis) {
                        return AgentToolResult.error(call.name(), "aborted",
                                "Tool did not finish before the run was stopped; its result was discarded");
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return AgentToolResult.error(call.name(), "interrupted", "Tool execution was interrupted");
            } catch (ExecutionException ex) {
                return AgentToolResult.error(call.name(), "execution_failed", describe(ex.getCause()));
            }
        }
    }

    private static boolean isParallel(ToolCall call, List<AgentTool> tools) {
        AgentTool tool = findTool(tools, call.name());
        return tool != null && call.hasValidArguments() && tool.executionMode() == AgentTool.ExecutionMode.PARALLEL;
    }

    private static AgentTool findTool(List<AgentTool> tools, String name) {
        for (AgentTool tool : tools) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    static AgentToolResult notExecuted(ToolCall call, String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", call.name());
        payload.addProperty("error", "Not executed: " + reason + ". Re-issue the call if it is still needed.");
        payload.addProperty("error_code", NOT_EXECUTED);
        return new AgentToolResult(payload, true, AgentToolResult.Control.NONE);
    }

    static String describe(Throwable ex) {
        if (ex == null) {
            return "Unknown error";
        }
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** Messages produced by this run and why it ended. */
    public record Outcome(List<AgentMessage> newMessages, EndReason reason) {
    }

    private record Batch(List<ToolResult> results, boolean paused, boolean terminate) {
    }

    private record Prepared(ToolCall call, AgentTool tool) {
    }

    private record Finalized(ToolCall call, AgentToolResult result) {
        ToolResult toMessage() {
            return new ToolResult(call.id(), call.name(), result.content(), result.isError(), System.currentTimeMillis());
        }
    }
}
