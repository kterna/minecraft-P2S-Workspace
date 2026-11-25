package com.p2s.agent.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Owns a session's run identities. Finish on the client thread after queued final
 * UI updates drain; callbacks arriving after completion are no longer accepted.
 */
public final class AgentRunGuard {
    private final String sessionId;
    private final long connectionEpoch;
    private RunIdentity currentRun;
    private boolean running;
    private boolean closed;

    public AgentRunGuard(String sessionId, long connectionEpoch) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.connectionEpoch = connectionEpoch;
    }

    public synchronized RunIdentity beginRun() {
        if (closed || running) {
            throw new IllegalStateException(closed ? "Session is closed" : "Agent is already running");
        }
        currentRun = new RunIdentity(sessionId, connectionEpoch, UUID.randomUUID());
        running = true;
        return currentRun;
    }

    public synchronized boolean isCurrent(RunIdentity run) {
        return !closed && running && run != null && run.equals(currentRun);
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized boolean isOpen(long epoch) {
        return !closed && connectionEpoch == epoch;
    }

    public synchronized boolean finishRun(RunIdentity run) {
        if (!running || !isCurrent(run)) {
            return false;
        }
        running = false;
        return true;
    }

    public synchronized boolean runIfCurrent(RunIdentity run, Runnable action) {
        if (!isCurrent(run)) {
            return false;
        }
        action.run();
        return true;
    }

    public synchronized void close() {
        closed = true;
        running = false;
    }

    public record RunIdentity(String sessionId, long connectionEpoch, UUID runId) {
    }
}
