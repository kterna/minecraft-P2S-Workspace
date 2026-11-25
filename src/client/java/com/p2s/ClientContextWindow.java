package com.p2s;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AssistantMessageEvent;
import com.p2s.agent.core.StreamFunction;
import com.p2s.agent.llm.OpenAiChatCompletions;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns the projection for one agent. All updates are made by its single run worker. */
final class ClientContextWindow implements StreamFunction {
    private final StreamFunction transport;
    private final Supplier<Settings> settings;
    private final SummaryRequest summarizer;
    private final BiConsumer<ClientHistoryCompactor.Trigger, String> onSummary;
    private final Consumer<String> onFailure;
    private volatile ClientHistoryCompactor.Checkpoint checkpoint = ClientHistoryCompactor.Checkpoint.EMPTY;
    private int lastChecked = -1;

    record Settings(boolean automatic, int window, int recent, String instructions) {}

    @FunctionalInterface
    interface SummaryRequest {
        String request(List<JsonObject> messages, AbortSignal signal) throws IOException;
    }

    ClientContextWindow(StreamFunction transport, Supplier<Settings> settings, SummaryRequest summarizer,
                        BiConsumer<ClientHistoryCompactor.Trigger, String> onSummary, Consumer<String> onFailure) {
        this.transport = transport;
        this.settings = settings;
        this.summarizer = summarizer;
        this.onSummary = onSummary;
        this.onFailure = onFailure;
    }

    static ClientContextWindow forClient(StreamFunction transport,
                                         BiConsumer<ClientHistoryCompactor.Trigger, String> onSummary,
                                         Consumer<String> onFailure) {
        return new ClientContextWindow(transport, () -> new Settings(P2SClientConfig.isAutoCompactEnabled(),
                P2SClientConfig.getContextWindowTokens(), P2SClientConfig.getCompactKeepRecentTokens(),
                P2SClientConfig.getCompactPrompt()), (messages, signal) ->
                OpenAiChatCompletions.completeText(messages, P2SClientConfig.llmRequestConfig(), signal),
                onSummary, onFailure);
    }

    ClientHistoryCompactor.Checkpoint checkpoint() {
        return checkpoint;
    }

    void restore(int firstKept, String summary, List<AgentMessage> transcript) {
        checkpoint = ClientHistoryCompactor.Checkpoint.restore(firstKept, summary, transcript);
    }

    Context view(Context raw) {
        return new Context(raw.systemPrompt(), checkpoint.project(raw.messages()), raw.tools());
    }

    @Override
    public AgentMessage.Assistant stream(Context raw, AbortSignal signal, Consumer<AssistantMessageEvent> sink) {
        Settings cfg = settings.get();
        Context request = check(raw, signal, cfg, ClientHistoryCompactor.Trigger.BEFORE_REQUEST);
        AgentMessage.Assistant result = transport.stream(request, signal, sink);
        if (!signal.isAborted() && cfg.automatic() && ClientHistoryCompactor.isOverflow(result)
                && compact(raw, signal, cfg, ClientHistoryCompactor.Trigger.OVERFLOW)) {
            return transport.stream(view(raw), signal, sink);
        }
        return result;
    }

    void betweenTurns(Context raw, AbortSignal signal) {
        check(raw, signal, settings.get(), ClientHistoryCompactor.Trigger.BETWEEN_TURNS);
    }

    boolean manual(Context raw, AbortSignal signal) {
        return compact(raw, signal, settings.get(), ClientHistoryCompactor.Trigger.MANUAL);
    }

    private Context check(Context raw, AbortSignal signal, Settings cfg, ClientHistoryCompactor.Trigger trigger) {
        Context projected = view(raw);
        if (cfg.automatic() && !signal.isAborted() && lastChecked != raw.messages().size()) {
            lastChecked = raw.messages().size();
            if (ClientHistoryCompactor.requestTokens(projected) >= ClientHistoryCompactor.threshold(cfg.window())
                    && compact(raw, signal, cfg, trigger)) {
                return view(raw);
            }
        }
        return projected;
    }

    private boolean compact(Context raw, AbortSignal signal, Settings cfg, ClientHistoryCompactor.Trigger trigger) {
        try {
            var candidate = ClientHistoryCompactor.compact(raw.messages(), checkpoint, cfg.window(), cfg.recent(),
                    trigger, cfg.instructions(), signal, messages -> summarizer.request(messages, signal));
            if (candidate == null || signal.isAborted()) {
                return false;
            }
            if (trigger != ClientHistoryCompactor.Trigger.MANUAL) {
                Context reduced = new Context(raw.systemPrompt(), candidate.project(raw.messages()), raw.tools());
                if (ClientHistoryCompactor.requestTokens(reduced) >= ClientHistoryCompactor.requestTokens(view(raw))) {
                    return false;
                }
            }
            checkpoint = candidate;
            onSummary.accept(trigger, candidate.summary());
            return true;
        } catch (Exception error) {
            if (!signal.isAborted()) {
                onFailure.accept(error.getMessage());
            }
            return false;
        }
    }
}
