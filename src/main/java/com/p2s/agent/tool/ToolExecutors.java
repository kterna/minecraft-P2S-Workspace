package com.p2s.agent.tool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded pool shared by the main agent and subagents for concurrent read-only tool calls.
 * A full queue rejects; the loop reports "executor_busy" rather than running the tool on the
 * caller's thread.
 */
public final class ToolExecutors {
    private static final int MAX_WORKERS = 4;
    private static final int MAX_QUEUED_TOOLS = 64;

    private ToolExecutors() {
    }

    public static Executor shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        private static final Executor INSTANCE = new ThreadPoolExecutor(
                MAX_WORKERS, MAX_WORKERS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_TOOLS), runnable -> {
                    Thread thread = new Thread(runnable, "p2s-tool-exec");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
