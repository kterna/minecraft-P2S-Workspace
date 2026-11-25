package com.p2s.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunGuardTest {
    @Test
    void drainsFinalUpdatesBeforeBecomingIdle() {
        AgentRunGuard guard = new AgentRunGuard("session", 1);
        var run = guard.beginRun();
        var clientQueue = new ArrayDeque<Runnable>();
        List<String> events = new ArrayList<>();
        clientQueue.add(() -> guard.runIfCurrent(run, () -> events.add("final reply")));
        clientQueue.add(() -> guard.finishRun(run));

        assertTrue(guard.isRunning());
        assertThrows(IllegalStateException.class, guard::beginRun);
        clientQueue.remove().run();
        assertEquals(List.of("final reply"), events);
        assertTrue(guard.isRunning());
        clientQueue.remove().run();
        assertFalse(guard.isRunning());
    }

    @Test
    void lateCallbacksCannotRestartStreamingAfterCompletion() {
        AgentRunGuard guard = new AgentRunGuard("session", 1);
        var run = guard.beginRun();
        assertTrue(guard.finishRun(run));
        List<String> streamed = new ArrayList<>();
        assertFalse(guard.runIfCurrent(run, () -> streamed.add("late token")));
        assertTrue(streamed.isEmpty());
    }

    @Test
    void previousRunCannotWriteOrFinishTheNextRun() {
        AgentRunGuard guard = new AgentRunGuard("session", 1);
        var first = guard.beginRun();
        Runnable lateReply = () -> guard.runIfCurrent(first, () -> fail("stale reply was delivered"));
        guard.finishRun(first);
        var second = guard.beginRun();

        lateReply.run();
        assertFalse(guard.finishRun(first));
        assertTrue(guard.isCurrent(second));
        assertTrue(guard.isRunning());
    }

    @Test
    void disconnectInvalidatesQueuedToolsAndSessionCannotBeReopened() {
        AgentRunGuard guard = new AgentRunGuard("session", 10);
        var run = guard.beginRun();
        Runnable queuedTool = () -> guard.runIfCurrent(run, () -> fail("detached tool was sent"));
        guard.close();

        queuedTool.run();
        assertFalse(guard.isOpen(10));
        assertFalse(guard.finishRun(run));
        assertThrows(IllegalStateException.class, guard::beginRun);
    }

    @Test
    void restoringSameSessionIdDoesNotAcceptItsPreviousRun() {
        AgentRunGuard first = new AgentRunGuard("saved-session", 1);
        var oldRun = first.beginRun();
        first.close();
        AgentRunGuard restored = new AgentRunGuard("saved-session", 1);
        var newRun = restored.beginRun();

        assertNotEquals(oldRun, newRun);
        assertFalse(restored.isCurrent(oldRun));
        assertTrue(restored.isCurrent(newRun));
    }

    @Test
    void connectionEpochRejectsReconnectedSession() {
        AgentRunGuard guard = new AgentRunGuard("session", 9);
        var run = guard.beginRun();
        assertTrue(guard.isOpen(9));
        assertFalse(guard.isOpen(10));
        assertEquals(9, run.connectionEpoch());
    }

    @Test
    void closeIsSerializedWithAlreadyAcceptedUpdates() throws Exception {
        AgentRunGuard guard = new AgentRunGuard("session", 1);
        var run = guard.beginRun();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var update = workers.submit(() -> guard.runIfCurrent(run, () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var close = workers.submit(guard::close);
                release.countDown();
                assertTrue(update.get(5, TimeUnit.SECONDS));
                close.get(5, TimeUnit.SECONDS);
                assertFalse(guard.runIfCurrent(run, () -> fail("update after close")));
            } finally {
                release.countDown();
            }
        }
    }
}
