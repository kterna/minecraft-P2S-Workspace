package com.p2s.agent.core;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentEvent.EndReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.p2s.agent.core.AgentTool.ExecutionMode.PARALLEL;
import static com.p2s.agent.core.AgentTool.ExecutionMode.SEQUENTIAL;
import static com.p2s.agent.core.ScriptedStream.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private AgentLoop.Outcome run(ScriptedStream stream, List<AgentTool> tools, AbortSignal signal,
                                  AgentLoopConfig.Builder builder) {
        return AgentLoop.run(List.of(AgentMessage.User.of("go")),
                new StreamFunction.Context("system", List.of(), tools),
                builder.build(), events::add, signal);
    }

    private AgentLoop.Outcome run(ScriptedStream stream, List<AgentTool> tools) {
        return run(stream, tools, AbortSignal.create(), AgentLoopConfig.builder(stream).toolExecutor(pool));
    }

    private static List<AgentMessage.ToolResult> results(AgentLoop.Outcome outcome) {
        return outcome.newMessages().stream()
                .filter(m -> m instanceof AgentMessage.ToolResult)
                .map(m -> (AgentMessage.ToolResult) m).toList();
    }

    private static String code(AgentMessage.ToolResult result) {
        JsonObject content = result.content();
        return content.has("error_code") ? content.get("error_code").getAsString() : "";
    }

    @Test
    void textOnlyRunEmitsPiEventSequence() {
        var stream = new ScriptedStream().then(text("hello"));
        var outcome = run(stream, List.of());

        assertEquals(EndReason.COMPLETED, outcome.reason());
        List<Class<?>> types = events.stream().<Class<?>>map(Object::getClass).toList();
        assertEquals(List.of(AgentEvent.AgentStart.class, AgentEvent.TurnStart.class,
                AgentEvent.MessageStart.class, AgentEvent.MessageEnd.class,
                AgentEvent.MessageStart.class, AgentEvent.MessageEnd.class,
                AgentEvent.TurnEnd.class, AgentEvent.AgentEnd.class), types);
        assertEquals(2, outcome.newMessages().size());
        assertEquals("hello", ((AgentMessage.Assistant) outcome.newMessages().get(1)).text());
    }

    @Test
    void toolResultsFollowTheirAssistantInCallOrderBeforeTheNextRequest() {
        var stream = new ScriptedStream().then(calls(call("a", "first"), call("b", "second"))).then(text("done"));
        var tools = List.of(
                tool("first", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok())),
                tool("second", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok())));
        var outcome = run(stream, tools);

        assertEquals(EndReason.COMPLETED, outcome.reason());
        List<AgentMessage> second = stream.requests.get(1).messages();
        assertInstanceOf(AgentMessage.Assistant.class, second.get(1));
        assertEquals("a", ((AgentMessage.ToolResult) second.get(2)).toolCallId());
        assertEquals("b", ((AgentMessage.ToolResult) second.get(3)).toolCallId());
    }

    @Test
    void adjacentReadsRunTogetherButNeverCrossWritesOrPlanUpdates() {
        AtomicInteger revision = new AtomicInteger();
        CountDownLatch pairStarted = new CountDownLatch(2);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ScriptedStream.ToolBody read = (id, s) -> {
            JsonObject payload = ok();
            if (id.startsWith("pair")) {
                pairStarted.countDown();
                // Only true when both pair reads are running at the same time.
                payload.addProperty("concurrent", pairStarted.await(2, TimeUnit.SECONDS));
            }
            payload.addProperty("revision", revision.get());
            order.add(id);
            return AgentToolResult.of(payload);
        };
        var tools = List.of(
                tool("read", PARALLEL, read),
                tool("write", SEQUENTIAL, (id, s) -> {
                    order.add(id);
                    revision.incrementAndGet();
                    return AgentToolResult.of(ok());
                }),
                tool("plan", SEQUENTIAL, (id, s) -> {
                    order.add(id);
                    return AgentToolResult.of(ok());
                }));
        var stream = new ScriptedStream().then(calls(call("pair-0", "read"), call("pair-1", "read"),
                call("write", "write"), call("read-2", "read"), call("plan", "plan"), call("read-3", "read")));
        var outcome = run(stream, tools);

        var results = results(outcome);
        assertEquals(List.of("pair-0", "pair-1", "write", "read-2", "plan", "read-3"),
                results.stream().map(AgentMessage.ToolResult::toolCallId).toList());
        assertTrue(results.get(0).content().get("concurrent").getAsBoolean());
        assertTrue(results.get(1).content().get("concurrent").getAsBoolean());
        assertEquals(0, results.get(0).content().get("revision").getAsInt());
        assertEquals(0, results.get(1).content().get("revision").getAsInt());
        assertEquals(1, results.get(3).content().get("revision").getAsInt());
        assertEquals(1, results.get(5).content().get("revision").getAsInt());
        assertEquals(List.of("write", "read-2", "plan", "read-3"), order.subList(2, 6));
    }

    @Test
    void identicalCallsKeepSeparateResults() {
        AtomicInteger sequence = new AtomicInteger();
        var tools = List.of(tool("read", SEQUENTIAL, (id, s) -> {
            JsonObject payload = ok();
            payload.addProperty("sequence", sequence.incrementAndGet());
            return AgentToolResult.of(payload);
        }));
        var stream = new ScriptedStream().then(calls(call("same", "read"), call("same", "read")));
        var results = results(run(stream, tools));

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).content().get("sequence").getAsInt());
        assertEquals(2, results.get(1).content().get("sequence").getAsInt());
    }

    @Test
    void pauseSkipsLaterCallsButClosesEveryCallId() {
        AtomicInteger laterRuns = new AtomicInteger();
        var tools = List.of(
                tool("ask", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok()).withControl(AgentToolResult.Control.PAUSE)),
                tool("read", PARALLEL, (id, s) -> {
                    laterRuns.incrementAndGet();
                    return AgentToolResult.of(ok());
                }));
        var stream = new ScriptedStream().then(calls(call("q", "ask"), call("r1", "read"), call("r2", "read")));
        var outcome = run(stream, tools);

        assertEquals(EndReason.PAUSED, outcome.reason());
        assertEquals(1, stream.requests.size(), "Paused runs must not call the model again");
        assertEquals(0, laterRuns.get());
        var results = results(outcome);
        assertEquals(List.of("q", "r1", "r2"), results.stream().map(AgentMessage.ToolResult::toolCallId).toList());
        assertFalse(results.get(0).isError());
        assertEquals(AgentLoop.NOT_EXECUTED, code(results.get(1)));
        assertEquals(AgentLoop.NOT_EXECUTED, code(results.get(2)));
    }

    @Test
    void abortDuringToolsSkipsTheRestAndEndsWithoutAnotherRequest() {
        AbortSignal signal = AbortSignal.create();
        AtomicBoolean secondRan = new AtomicBoolean();
        var tools = List.of(
                tool("write", SEQUENTIAL, (id, s) -> {
                    signal.abort("Stopped by user");
                    return AgentToolResult.of(ok());
                }),
                tool("read", SEQUENTIAL, (id, s) -> {
                    secondRan.set(true);
                    return AgentToolResult.of(ok());
                }));
        var stream = new ScriptedStream().then(calls(call("w", "write"), call("r", "read")));
        var outcome = run(stream, tools, signal, AgentLoopConfig.builder(stream).toolExecutor(pool));

        assertEquals(EndReason.ABORTED, outcome.reason());
        assertFalse(secondRan.get());
        assertEquals(1, stream.requests.size());
        var results = results(outcome);
        assertTrue(results.get(0).content().get("ok").getAsBoolean(), "A finished call keeps its real result");
        assertEquals(AgentLoop.NOT_EXECUTED, code(results.get(1)));
        assertInstanceOf(AgentEvent.AgentEnd.class, events.getLast());
    }

    @Test
    void abortedOrFailedResponsesNeverExecuteTools() {
        AtomicBoolean ran = new AtomicBoolean();
        var tools = List.of(tool("write", SEQUENTIAL, (id, s) -> {
            ran.set(true);
            return AgentToolResult.of(ok());
        }));
        AbortSignal signal = AbortSignal.create();
        var aborted = new ScriptedStream().then(call -> {
            call.signal().abort("stop");
            return new AgentMessage.Assistant("partial", "", List.of(ScriptedStream.call("w", "write")),
                    AgentMessage.StopReason.ABORTED, "aborted", 0L);
        });
        assertEquals(EndReason.ABORTED, run(aborted, tools, signal, AgentLoopConfig.builder(aborted)).reason());

        var failed = new ScriptedStream().then(AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, "HTTP 500"));
        assertEquals(EndReason.ERROR, run(failed, tools).reason());
        assertFalse(ran.get());
    }

    @Test
    void invalidArgumentsAndUnknownToolsAreReportedNotExecuted() {
        AtomicBoolean ran = new AtomicBoolean();
        var tools = List.of(tool("write", SEQUENTIAL, (id, s) -> {
            ran.set(true);
            return AgentToolResult.of(ok());
        }));
        var stream = new ScriptedStream()
                .then(calls(AgentMessage.ToolCall.invalid("bad", "write", "{\"path\":", "incomplete"),
                        call("ghost", "not_a_tool")))
                .then(text("retrying"));
        var outcome = run(stream, tools);

        assertFalse(ran.get());
        var results = results(outcome);
        assertEquals("invalid_arguments", code(results.get(0)));
        assertEquals("unknown_tool", code(results.get(1)));
        assertEquals(2, stream.requests.size(), "The model gets a chance to correct the call");
    }

    @Test
    void throwingToolBecomesErrorResult() {
        var tools = List.of(tool("read", SEQUENTIAL, (id, s) -> {
            throw new IllegalStateException("read failed");
        }));
        var stream = new ScriptedStream().then(calls(call("x", "read")));
        var result = results(run(stream, tools)).getFirst();
        assertTrue(result.isError());
        assertEquals("execution_failed", code(result));
        assertEquals("read failed", result.content().get("error").getAsString());
    }

    @Test
    void saturatedExecutorReportsBusyWithoutRunningOnTheLoopThread() {
        AtomicBoolean ran = new AtomicBoolean();
        var tools = List.of(tool("read", PARALLEL, (id, s) -> {
            ran.set(true);
            return AgentToolResult.of(ok());
        }));
        var stream = new ScriptedStream().then(calls(call("a", "read"), call("b", "read")));
        var outcome = run(stream, tools, AbortSignal.create(), AgentLoopConfig.builder(stream)
                .toolExecutor(task -> {
                    throw new RejectedExecutionException("full");
                }));
        assertFalse(ran.get());
        for (var result : results(outcome)) {
            assertEquals("executor_busy", code(result));
        }
    }

    @Test
    void stopDoesNotWaitForAToolThatIgnoresTheSignal() {
        AbortSignal signal = AbortSignal.create();
        CountDownLatch release = new CountDownLatch(1);
        var tools = List.of(tool("read", PARALLEL, (id, s) -> {
            release.await(10, TimeUnit.SECONDS);
            return AgentToolResult.of(ok());
        }));
        var stream = new ScriptedStream().then(calls(call("a", "read"), call("b", "read")));
        pool.execute(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            signal.abort("stop");
        });
        long started = System.nanoTime();
        var outcome = run(stream, tools, signal, AgentLoopConfig.builder(stream).toolExecutor(pool).abortGraceMillis(100));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();

        assertEquals(EndReason.ABORTED, outcome.reason());
        assertTrue(elapsedMs < 3000, "Stop waited " + elapsedMs + "ms");
        for (var result : results(outcome)) {
            assertEquals("aborted", code(result));
        }
    }

    @Test
    void steeringIsInjectedAfterTheToolBatchAndFollowUpsAfterTheAgentWouldStop() {
        var tools = List.of(tool("read", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok())));
        var stream = new ScriptedStream().then(calls(call("a", "read"))).then(text("first")).then(text("second"));
        List<AgentMessage> steering = new ArrayList<>(List.of(AgentMessage.User.of("also check the roof")));
        List<AgentMessage> followUps = new ArrayList<>(List.of(AgentMessage.User.of("now the door")));
        AtomicInteger steeringPolls = new AtomicInteger();
        var outcome = run(stream, tools, AbortSignal.create(), AgentLoopConfig.builder(stream)
                .steeringMessages(() -> {
                    // Nothing at start; the message arrives while the first turn runs.
                    if (steeringPolls.getAndIncrement() == 0) {
                        return List.of();
                    }
                    List<AgentMessage> drained = List.copyOf(steering);
                    steering.clear();
                    return drained;
                })
                .followUpMessages(() -> {
                    List<AgentMessage> drained = List.copyOf(followUps);
                    followUps.clear();
                    return drained;
                }));

        assertEquals(EndReason.COMPLETED, outcome.reason());
        assertEquals(3, stream.requests.size());
        List<AgentMessage> second = stream.requests.get(1).messages();
        assertInstanceOf(AgentMessage.ToolResult.class, second.get(second.size() - 2));
        assertEquals("also check the roof", ((AgentMessage.User) second.getLast()).content());
        assertEquals("now the door", ((AgentMessage.User) stream.requests.get(2).messages().getLast()).content());
    }

    @Test
    void policyStopEndsAfterTheTurnAndTransformOnlyAffectsTheRequest() {
        var tools = List.of(tool("read", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok())));
        var stream = new ScriptedStream().then(calls(call("a", "read"))).then(calls(call("b", "read")));
        var outcome = run(stream, tools, AbortSignal.create(), AgentLoopConfig.builder(stream)
                .transformContext(messages -> messages.subList(Math.max(0, messages.size() - 1), messages.size()))
                .shouldStopAfterTurn(turn -> turn.turnIndex() + 1 >= 2));

        assertEquals(EndReason.STOPPED_BY_POLICY, outcome.reason());
        assertEquals(2, stream.requests.size());
        assertEquals(1, stream.requests.get(1).messages().size(), "transformContext shapes the request view");
        assertEquals(5, outcome.newMessages().size(), "the transcript itself is not trimmed");
    }
}
