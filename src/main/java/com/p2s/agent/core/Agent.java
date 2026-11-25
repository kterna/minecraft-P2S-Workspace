package com.p2s.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Stateful wrapper around {@link AgentLoop} (port of pi-agent-core {@code Agent}).
 *
 * <p>The agent owns the transcript, runs one loop at a time, exposes steering / follow-up
 * queues and emits lifecycle events to subscribers. {@link #prompt} and {@link #continueRun}
 * block the calling thread until the run ends; {@link #abort}, {@link #steer} and
 * {@link #followUp} may be called from any thread.
 */
public final class Agent {
    private final Object lock = new Object();
    private final AgentLoopConfig baseConfig;
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final PendingMessageQueue steeringQueue;
    private final PendingMessageQueue followUpQueue;

    private String systemPrompt;
    private List<AgentTool> tools;
    private List<AgentMessage> messages;
    private boolean running;
    private AbortSignal activeSignal;
    private String errorMessage = "";

    public Agent(AgentLoopConfig config, String systemPrompt, List<AgentTool> tools, List<AgentMessage> messages) {
        this(config, systemPrompt, tools, messages, QueueMode.ONE_AT_A_TIME, QueueMode.ONE_AT_A_TIME);
    }

    public Agent(AgentLoopConfig config, String systemPrompt, List<AgentTool> tools, List<AgentMessage> messages,
                 QueueMode steeringMode, QueueMode followUpMode) {
        this.baseConfig = config;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        this.steeringQueue = new PendingMessageQueue(steeringMode);
        this.followUpQueue = new PendingMessageQueue(followUpMode);
    }

    /** Subscribe to lifecycle events; returns an unsubscribe action. */
    public Runnable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public List<AgentMessage> messages() {
        synchronized (lock) {
            return List.copyOf(messages);
        }
    }

    /** Replace the transcript, e.g. after restoring or compacting. Only allowed while idle. */
    public void replaceMessages(List<AgentMessage> replacement) {
        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("Cannot replace messages while the agent is running");
            }
            messages = new ArrayList<>(replacement);
        }
    }

    public String systemPrompt() {
        synchronized (lock) {
            return systemPrompt;
        }
    }

    public void setSystemPrompt(String value) {
        synchronized (lock) {
            systemPrompt = value == null ? "" : value;
        }
    }

    public List<AgentTool> tools() {
        synchronized (lock) {
            return tools;
        }
    }

    public void setTools(List<AgentTool> value) {
        synchronized (lock) {
            tools = value == null ? List.of() : List.copyOf(value);
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    public String errorMessage() {
        synchronized (lock) {
            return errorMessage;
        }
    }

    /** Queue a message injected after the current turn's tool calls finish. */
    public void steer(AgentMessage message) {
        steeringQueue.enqueue(message);
    }

    /** Queue a message processed only when the agent would otherwise stop. */
    public void followUp(AgentMessage message) {
        followUpQueue.enqueue(message);
    }

    public List<AgentMessage> clearSteeringQueue() {
        return steeringQueue.clear();
    }

    public List<AgentMessage> clearFollowUpQueue() {
        return followUpQueue.clear();
    }

    public boolean hasQueuedMessages() {
        return steeringQueue.hasItems() || followUpQueue.hasItems();
    }

    /** Abort the active run, if any. Returns true when a run was signalled. */
    public boolean abort(String reason) {
        AbortSignal signal;
        synchronized (lock) {
            signal = activeSignal;
        }
        return signal != null && signal.abort(reason);
    }

    /** Clear transcript and queues. Only allowed while idle. */
    public void reset() {
        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("Cannot reset while the agent is running");
            }
            messages = new ArrayList<>();
            errorMessage = "";
        }
        steeringQueue.clear();
        followUpQueue.clear();
    }

    /** Run the loop with new prompt messages. Blocks until the run ends. */
    public AgentLoop.Outcome prompt(List<AgentMessage> prompts) {
        List<AgentMessage> input = List.copyOf(prompts);
        return runWithLifecycle(signal -> AgentLoop.run(input, snapshot(), loopConfig(), this::processEvent, signal));
    }

    /**
     * Continue from the current transcript. When the transcript ends with an assistant
     * message, queued steering or follow-up messages are used as the next prompt.
     */
    public AgentLoop.Outcome continueRun() {
        AgentMessage last;
        synchronized (lock) {
            last = messages.isEmpty() ? null : messages.getLast();
        }
        if (last == null) {
            throw new IllegalStateException("No messages to continue from");
        }
        if (last instanceof AgentMessage.Assistant) {
            List<AgentMessage> queued = steeringQueue.drain();
            if (queued.isEmpty()) {
                queued = followUpQueue.drain();
            }
            if (queued.isEmpty()) {
                throw new IllegalStateException("Cannot continue from an assistant message");
            }
            return prompt(queued);
        }
        return runWithLifecycle(signal -> AgentLoop.runContinue(snapshot(), loopConfig(), this::processEvent, signal));
    }

    private StreamFunction.Context snapshot() {
        synchronized (lock) {
            return new StreamFunction.Context(systemPrompt, messages, tools);
        }
    }

    private AgentLoopConfig loopConfig() {
        Function<AgentLoopConfig.TurnContext, List<AgentMessage>> userPrepare = baseConfig.prepareNextTurn();
        Function<AgentLoopConfig.TurnContext, List<AgentMessage>> prepare = userPrepare == null ? null : turn -> {
            List<AgentMessage> replacement = userPrepare.apply(turn);
            if (replacement != null) {
                synchronized (lock) {
                    messages = new ArrayList<>(replacement);
                }
            }
            return replacement;
        };
        return new AgentLoopConfig(baseConfig.streamFunction(), baseConfig.transformContext(),
                baseConfig.beforeToolCall(), baseConfig.afterToolCall(), baseConfig.shouldStopAfterTurn(), prepare,
                steeringQueue::drain, followUpQueue::drain, baseConfig.toolExecutor(), baseConfig.abortGraceMillis());
    }

    private AgentLoop.Outcome runWithLifecycle(Function<AbortSignal, AgentLoop.Outcome> executor) {
        AbortSignal signal = AbortSignal.create();
        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("Agent is already running; use steer() or followUp()");
            }
            running = true;
            activeSignal = signal;
            errorMessage = "";
        }
        try {
            return executor.apply(signal);
        } catch (RuntimeException ex) {
            return handleRunFailure(ex, signal);
        } finally {
            synchronized (lock) {
                running = false;
                activeSignal = null;
            }
        }
    }

    /** A hook or listener threw: close the run with a failure message, as pi does. */
    private AgentLoop.Outcome handleRunFailure(RuntimeException error, AbortSignal signal) {
        boolean aborted = signal.isAborted();
        AgentMessage.Assistant failure = AgentMessage.Assistant.failure(
                aborted ? AgentMessage.StopReason.ABORTED : AgentMessage.StopReason.ERROR, AgentLoop.describe(error));
        AgentEvent.EndReason reason = aborted ? AgentEvent.EndReason.ABORTED : AgentEvent.EndReason.ERROR;
        for (AgentEvent event : List.of(new AgentEvent.MessageStart(failure), new AgentEvent.MessageEnd(failure),
                new AgentEvent.TurnEnd(failure, List.of()), new AgentEvent.AgentEnd(List.of(failure), reason))) {
            try {
                processEvent(event);
            } catch (RuntimeException ignored) {
                // The failure path must always reach AgentEnd for the remaining listeners.
            }
        }
        return new AgentLoop.Outcome(List.of(failure), reason);
    }

    private void processEvent(AgentEvent event) {
        synchronized (lock) {
            if (event instanceof AgentEvent.MessageEnd end) {
                messages.add(end.message());
            } else if (event instanceof AgentEvent.TurnEnd turnEnd && turnEnd.message().failed()) {
                errorMessage = turnEnd.message().errorMessage();
            }
        }
        for (Consumer<AgentEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    public enum QueueMode {
        ALL,
        ONE_AT_A_TIME
    }

    private static final class PendingMessageQueue {
        private final List<AgentMessage> items = new ArrayList<>();
        private final QueueMode mode;

        PendingMessageQueue(QueueMode mode) {
            this.mode = mode == null ? QueueMode.ONE_AT_A_TIME : mode;
        }

        synchronized void enqueue(AgentMessage message) {
            if (message != null) {
                items.add(message);
            }
        }

        synchronized boolean hasItems() {
            return !items.isEmpty();
        }

        synchronized List<AgentMessage> drain() {
            if (items.isEmpty()) {
                return List.of();
            }
            if (mode == QueueMode.ALL) {
                List<AgentMessage> drained = List.copyOf(items);
                items.clear();
                return drained;
            }
            return List.of(items.removeFirst());
        }

        synchronized List<AgentMessage> clear() {
            List<AgentMessage> dropped = List.copyOf(items);
            items.clear();
            return dropped;
        }
    }
}
