package com.p2s;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.StreamFunction;
import com.p2s.store.SessionTranscript;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientContextWindowTest {
    @Test
    void reviewAfterManyShortTurnsStillSeesTheFirstUserTask() {
        List<AgentMessage> raw = conversation(450);
        var review = AgentMessage.User.of("Player review: APPLIED dome and walls");
        raw.add(review);
        ClientContextWindow window = window((request, signal, sink) -> {
            assertEquals(raw, request.messages());
            return assistant("done");
        }, (messages, signal) -> { throw new AssertionError("well under 128k"); });
        window.stream(context(raw), AbortSignal.none(), ignored -> {});
        var restored = SessionTranscript.decode(SessionTranscript.encode("system", raw));
        assertEquals(raw, restored);
        assertEquals(raw.getFirst(), restored.getFirst());
        assertEquals(review, restored.getLast());
    }

    @Test
    void compactMaintainsToolBatchesAndUpdatesPreviousSummaryOnlyWithTheNewPrefix() throws Exception {
        List<AgentMessage> raw = conversation(60);
        var call = new AgentMessage.Assistant("", "", List.of(AgentMessage.ToolCall.valid("1", "read", "{}", new JsonObject())),
                AgentMessage.StopReason.TOOL_USE, "", 1);
        raw.add(call);
        raw.add(new AgentMessage.ToolResult("1", "read", new JsonObject(), false, 2));
        raw.add(AgentMessage.User.of("review recent"));
        var checkpoint = ClientHistoryCompactor.compact(raw, ClientHistoryCompactor.Checkpoint.EMPTY, 128_000, 160,
                ClientHistoryCompactor.Trigger.MANUAL, "", AbortSignal.none(), request -> {
                    String text = request.getLast().get("content").getAsString();
                    assertFalse(text.contains("review recent"));
                    assertTrue(text.contains("step 0 "));
                    return "summary one";
                });
        var first = checkpoint.project(raw);
        assertEquals(raw.getFirst(), first.getFirst());
        assertTrue(first.contains(call));
        assertEquals(raw.get(raw.size() - 2), first.get(first.indexOf(call) + 1));
        assertEquals(raw.getLast(), first.getLast());
        assertEquals(checkpoint, ClientHistoryCompactor.Checkpoint.restore(checkpoint.firstKept(),
                checkpoint.summary(), SessionTranscript.decode(SessionTranscript.encode("", raw))));
        for (int i = 0; i < 40; i++) raw.add(assistant("later " + i + " " + "z".repeat(60)));
        var second = ClientHistoryCompactor.compact(raw, checkpoint, 128_000, 160, ClientHistoryCompactor.Trigger.MANUAL,
                "", AbortSignal.none(), request -> {
                    String text = request.getLast().get("content").getAsString();
                    assertTrue(text.contains("summary one"));
                    assertTrue(text.contains("review recent"));
                    assertFalse(text.contains("step 0 "));
                    return "summary two";
                });
        assertTrue(second.firstKept() > checkpoint.firstKept());
        assertEquals(raw.getFirst(), second.project(raw).getFirst());
    }

    @Test
    void tokenWindowCompactsBetweenToolTurnsBeforeTheNextRequest() {
        assertEquals(111616, ClientHistoryCompactor.threshold(128000));
        assertEquals(3000, ClientHistoryCompactor.threshold(4000));
        List<AgentMessage> raw = conversation(80);
        var summaries = new AtomicInteger();
        var window = new ClientContextWindow((ctx, signal, sink) -> {
            assertEquals(raw.getFirst(), ctx.messages().getFirst());
            assertEquals(raw.getLast(), ctx.messages().getLast());
            assertTrue(ctx.messages().size() < raw.size());
            return assistant("done");
        }, () -> new ClientContextWindow.Settings(true, 1800, 180, ""), (messages, signal) -> "short summary",
                (trigger, text) -> {
                    assertEquals(ClientHistoryCompactor.Trigger.BETWEEN_TURNS, trigger);
                    summaries.incrementAndGet();
                }, why -> fail(why));
        window.betweenTurns(context(raw), AbortSignal.none());
        window.stream(context(raw), AbortSignal.none(), ignored -> {});
        assertEquals(1, summaries.get());
    }

    @Test
    void retriesOnlyOneOverflowAndDoesNotAppendThePromptTwice() {
        var count = new AtomicInteger();
        var summaries = new AtomicInteger();
        List<AgentMessage> raw = conversation(80);
        ClientContextWindow window = window((ctx, signal, sink) -> {
            if (count.incrementAndGet() == 2) {
                assertEquals(raw.getFirst(), ctx.messages().getFirst());
                assertTrue(ctx.messages().size() < raw.size());
                assertEquals(raw.getLast(), ctx.messages().getLast());
            }
            return AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, "HTTP 400: context_length_exceeded");
        }, (messages, signal) -> { summaries.incrementAndGet(); return "short summary"; });
        assertTrue(window.stream(context(raw), AbortSignal.none(), ignored -> {}).failed());
        assertEquals(2, count.get());
        assertEquals(1, summaries.get());
        assertEquals(81, raw.size());
        assertFalse(ClientHistoryCompactor.isOverflow(AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR,
                "HTTP 400: invalid temperature")));
    }

    @Test
    void failedOrCancelledSummaryDoesNotAdvanceTheCheckpointOrRetryTheRequest() {
        for (boolean abort : List.of(true, false)) {
            var signal = AbortSignal.create();
            var calls = new AtomicInteger();
            var window = new ClientContextWindow((context, s, sink) -> {
                calls.incrementAndGet();
                return AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, "prompt is too long");
            }, () -> new ClientContextWindow.Settings(true, 128000, 20000, ""), (messages, s) -> {
                if (abort) signal.abort("test");
                throw new IOException("test");
            }, (trigger, summary) -> fail(), why -> {});
            assertTrue(window.stream(context(conversation(80)), signal, ignored -> {}).failed());
            assertEquals(1, calls.get());
            assertEquals(ClientHistoryCompactor.Checkpoint.EMPTY, window.checkpoint());
        }
    }

    @Test
    void oversizedLatestBatchIsSummarizedAsAUnitWhenTheProviderRejectsIt() {
        var count = new AtomicInteger();
        List<AgentMessage> raw = new ArrayList<>();
        raw.add(AgentMessage.User.of("test"));
        raw.add(new AgentMessage.Assistant("", "", List.of(AgentMessage.ToolCall.valid("1", "read", "{}", new JsonObject())),
                AgentMessage.StopReason.TOOL_USE, "", 1));
        JsonObject payload = new JsonObject();
        payload.addProperty("data", "a".repeat(18000));
        raw.add(new AgentMessage.ToolResult("1", "read", payload, false, 2));
        var window = window((context, signal, sink) -> {
            if (count.incrementAndGet() == 1) {
                return AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, "maximum context length");
            }
            assertEquals(2, context.messages().size());
            return assistant("done");
        }, (messages, signal) -> "huge file reviewed");
        assertFalse(window.stream(context(raw), AbortSignal.none(), ignored -> {}).failed());
        assertEquals(2, count.get());
    }

    @Test
    void oldPromptsMigrateWhilePersonalInstructionsAndTranscriptMetadataSurvive() {
        assertEquals("", AgentPrompt.migrate(P2SDefaults.DEFAULT_SYSTEM_PROMPT));
        assertEquals("请用石砖。", AgentPrompt.migrate(P2SDefaults.DEFAULT_SYSTEM_PROMPT + "\n请用石砖。"));
        assertEquals("", AgentPrompt.migrate("Tools: read_workspace_state, upsert_part"));
        assertEquals("请用石砖。", AgentPrompt.migrate("请用石砖。"));
        assertFalse(AgentPrompt.compose(true, "请用石砖。").contains("propose_patch"));

        var failure = AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, "network disconnected");
        var truncated = new AgentMessage.Assistant("partial", "private thinking", List.of(), AgentMessage.StopReason.LENGTH, "", 14L);
        List<AgentMessage> messages = List.of(AgentMessage.User.of("keep me"), failure, truncated);
        assertEquals(messages, SessionTranscript.decode(SessionTranscript.encode("system", messages)));
    }

    private static ClientContextWindow window(StreamFunction stream, ClientContextWindow.SummaryRequest summarize) {
        return new ClientContextWindow(stream, () -> new ClientContextWindow.Settings(true, 128_000, 20_000, ""),
                summarize, (trigger, summary) -> {}, why -> fail(why));
    }

    private static StreamFunction.Context context(List<AgentMessage> raw) {
        return new StreamFunction.Context("system", raw, List.of());
    }

    private static List<AgentMessage> conversation(int count) {
        List<AgentMessage> raw = new ArrayList<>();
        raw.add(AgentMessage.User.of("Build a round observatory"));
        for (int i = 0; i < count; i++) raw.add(assistant("step " + i + " " + "x".repeat(40)));
        return raw;
    }

    private static AgentMessage.Assistant assistant(String value) {
        return new AgentMessage.Assistant(value, "", List.of(), AgentMessage.StopReason.STOP, "", 17L);
    }
}
