package com.p2s.agent.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.p2s.agent.core.AgentTool.ExecutionMode.SEQUENTIAL;
import static com.p2s.agent.core.ScriptedStream.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTest {
    private static Agent agent(ScriptedStream stream, List<AgentTool> tools) {
        return new Agent(AgentLoopConfig.builder(stream).build(), "system", tools, List.of());
    }

    @Test
    void promptAppendsTheWholeTurnToTheTranscript() {
        var stream = new ScriptedStream().then(calls(call("a", "read"))).then(text("finished"));
        var agent = agent(stream, List.of(tool("read", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok()))));
        var outcome = agent.prompt(List.of(AgentMessage.User.of("build a tower")));

        assertEquals(AgentEvent.EndReason.COMPLETED, outcome.reason());
        List<AgentMessage> transcript = agent.messages();
        assertEquals(4, transcript.size());
        assertInstanceOf(AgentMessage.User.class, transcript.get(0));
        assertInstanceOf(AgentMessage.Assistant.class, transcript.get(1));
        assertInstanceOf(AgentMessage.ToolResult.class, transcript.get(2));
        assertEquals("finished", ((AgentMessage.Assistant) transcript.get(3)).text());
        assertFalse(agent.isRunning());
    }

    @Test
    void secondPromptWhileRunningIsRejectedAndAbortEndsTheRun() throws Exception {
        CountDownLatch inRequest = new CountDownLatch(1);
        var stream = new ScriptedStream().then(call -> {
            inRequest.countDown();
            CountDownLatch aborted = new CountDownLatch(1);
            call.signal().onAbort(aborted::countDown);
            try {
                aborted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentMessage.Assistant.failure(AgentMessage.StopReason.ABORTED, "Operation aborted");
        });
        var agent = agent(stream, List.of());
        var running = CompletableFuture.supplyAsync(() -> agent.prompt(List.of(AgentMessage.User.of("go"))));
        assertTrue(inRequest.await(5, TimeUnit.SECONDS));

        assertTrue(agent.isRunning());
        assertThrows(IllegalStateException.class, () -> agent.prompt(List.of(AgentMessage.User.of("again"))));
        assertTrue(agent.abort("Stopped by user"));
        var outcome = running.get(5, TimeUnit.SECONDS);

        assertEquals(AgentEvent.EndReason.ABORTED, outcome.reason());
        assertFalse(agent.isRunning());
        assertFalse(agent.abort("again"), "No active run to abort");
        var last = (AgentMessage.Assistant) agent.messages().getLast();
        assertEquals(AgentMessage.StopReason.ABORTED, last.stopReason());
    }

    @Test
    void messagesSteeredDuringARunReachTheNextRequest() {
        List<Agent> holder = new ArrayList<>();
        var stream = new ScriptedStream()
                .then(call -> {
                    holder.getFirst().steer(AgentMessage.User.of("make it taller"));
                    return calls(ScriptedStream.call("a", "read"));
                })
                .then(text("ok"));
        var agent = agent(stream, List.of(tool("read", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok()))));
        holder.add(agent);
        agent.prompt(List.of(AgentMessage.User.of("build")));

        List<AgentMessage> second = stream.requests.get(1).messages();
        assertEquals("make it taller", ((AgentMessage.User) second.getLast()).content());
        assertFalse(agent.hasQueuedMessages());
    }

    @Test
    void continueFromAnAssistantMessageUsesQueuedFollowUps() {
        var stream = new ScriptedStream().then(text("first")).then(text("second"));
        var agent = agent(stream, List.of());
        agent.prompt(List.of(AgentMessage.User.of("one")));
        assertThrows(IllegalStateException.class, agent::continueRun);

        agent.followUp(AgentMessage.User.of("two"));
        agent.continueRun();
        assertEquals("two", ((AgentMessage.User) stream.requests.get(1).messages().getLast()).content());
        assertEquals("second", ((AgentMessage.Assistant) agent.messages().getLast()).text());
    }

    @Test
    void throwingListenerFailsTheRunButStillDeliversAgentEnd() {
        var stream = new ScriptedStream().then(text("hello"));
        var agent = agent(stream, List.of());
        List<AgentEvent> seen = new ArrayList<>();
        agent.subscribe(seen::add);
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.MessageEnd end && end.message() instanceof AgentMessage.Assistant) {
                throw new IllegalStateException("UI bug");
            }
        });
        var outcome = agent.prompt(List.of(AgentMessage.User.of("go")));

        assertEquals(AgentEvent.EndReason.ERROR, outcome.reason());
        assertInstanceOf(AgentEvent.AgentEnd.class, seen.getLast());
        assertFalse(agent.isRunning());
        assertEquals("UI bug", agent.errorMessage());
    }

    @Test
    void prepareNextTurnReplacementBecomesTheTranscript() {
        var stream = new ScriptedStream().then(calls(call("a", "read"))).then(text("after compaction"));
        var config = AgentLoopConfig.builder(stream)
                .prepareNextTurn(turn -> turn.turnIndex() == 0 ? List.of(AgentMessage.User.of("[summary]")) : null)
                .build();
        var agent = new Agent(config, "system",
                List.of(tool("read", SEQUENTIAL, (id, s) -> AgentToolResult.of(ok()))), List.of());
        agent.prompt(List.of(AgentMessage.User.of("go")));

        assertEquals(List.of("[summary]"), stream.requests.get(1).messages().stream()
                .map(m -> ((AgentMessage.User) m).content()).toList());
        List<AgentMessage> transcript = agent.messages();
        assertEquals(2, transcript.size());
        assertEquals("[summary]", ((AgentMessage.User) transcript.get(0)).content());
        assertEquals("after compaction", ((AgentMessage.Assistant) transcript.get(1)).text());
    }
}
