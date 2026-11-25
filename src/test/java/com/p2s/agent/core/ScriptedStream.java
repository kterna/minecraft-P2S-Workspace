package com.p2s.agent.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Test transport: replays scripted responses and records each request's context. */
final class ScriptedStream implements StreamFunction {
    private final Deque<Function<Call, AgentMessage.Assistant>> script = new ArrayDeque<>();
    final List<Context> requests = new ArrayList<>();

    record Call(Context context, AbortSignal signal, Consumer<AssistantMessageEvent> sink) {
    }

    ScriptedStream then(AgentMessage.Assistant response) {
        script.add(call -> response);
        return this;
    }

    ScriptedStream then(Function<Call, AgentMessage.Assistant> response) {
        script.add(response);
        return this;
    }

    @Override
    public synchronized AgentMessage.Assistant stream(Context context, AbortSignal signal,
                                                      Consumer<AssistantMessageEvent> sink) {
        requests.add(context);
        Function<Call, AgentMessage.Assistant> next = script.poll();
        if (next == null) {
            return text("done");
        }
        return next.apply(new Call(context, signal, sink));
    }

    static AgentMessage.Assistant text(String text) {
        return new AgentMessage.Assistant(text, "", List.of(), AgentMessage.StopReason.STOP, "", 0L);
    }

    static AgentMessage.Assistant calls(AgentMessage.ToolCall... calls) {
        return new AgentMessage.Assistant("", "", List.of(calls), AgentMessage.StopReason.TOOL_USE, "", 0L);
    }

    static AgentMessage.ToolCall call(String id, String name) {
        return AgentMessage.ToolCall.valid(id, name, "{}", new JsonObject());
    }

    static JsonObject ok() {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        return payload;
    }

    /** A tool backed by a lambda. */
    static AgentTool tool(String name, AgentTool.ExecutionMode mode, ToolBody body) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public JsonObject declaration() {
                JsonObject function = new JsonObject();
                function.addProperty("name", name);
                JsonObject declaration = new JsonObject();
                declaration.addProperty("type", "function");
                declaration.add("function", function);
                return declaration;
            }

            @Override
            public ExecutionMode executionMode() {
                return mode;
            }

            @Override
            public AgentToolResult execute(String toolCallId, JsonObject arguments, AbortSignal signal) throws Exception {
                return body.run(toolCallId, signal);
            }
        };
    }

    @FunctionalInterface
    interface ToolBody {
        AgentToolResult run(String toolCallId, AbortSignal signal) throws Exception;
    }
}
