package com.p2s.agent.core;

import com.google.gson.JsonObject;

/** A tool the loop can execute (pi: AgentTool). */
public interface AgentTool {
    String name();

    /** Provider declaration advertised to the model, e.g. an OpenAI function object. */
    JsonObject declaration();

    /**
     * PARALLEL tools may run concurrently with adjacent PARALLEL calls of the same batch.
     * A SEQUENTIAL call is a barrier: it starts after everything before it finished and
     * nothing after it starts until it completes.
     */
    default ExecutionMode executionMode() {
        return ExecutionMode.SEQUENTIAL;
    }

    /** Throw on failure; the loop turns exceptions into error results for the model. */
    AgentToolResult execute(String toolCallId, JsonObject arguments, AbortSignal signal) throws Exception;

    enum ExecutionMode {
        SEQUENTIAL,
        PARALLEL
    }
}
