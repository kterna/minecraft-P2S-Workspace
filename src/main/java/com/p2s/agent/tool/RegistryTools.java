package com.p2s.agent.tool;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.AgentToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Exposes registry handlers as loop tools in catalog order. Read-only catalog tools run in
 * parallel with adjacent read-only calls; every tool with an effect is a barrier.
 */
public final class RegistryTools {
    private RegistryTools() {
    }

    /**
     * @param isActive extra liveness check (run ownership); combined with the abort signal
     * @param control  decides whether a finished call hands control to the user; may be null
     */
    public static List<AgentTool> from(ToolRegistry registry, BooleanSupplier isActive,
                                       BiFunction<String, JsonObject, AgentToolResult.Control> control) {
        return from(registry, ToolCatalog.all(), isActive, control);
    }

    /** Tools of {@code catalog} that the registry implements and allows, in catalog order. */
    public static List<AgentTool> from(ToolRegistry registry, List<ToolDefinition> catalog, BooleanSupplier isActive,
                                       BiFunction<String, JsonObject, AgentToolResult.Control> control) {
        List<AgentTool> tools = new ArrayList<>();
        for (ToolDefinition definition : catalog) {
            if (registry.isAvailable(definition.name())) {
                tools.add(new Adapter(definition, registry, isActive, control));
            }
        }
        return List.copyOf(tools);
    }

    private record Adapter(ToolDefinition definition, ToolRegistry registry, BooleanSupplier isActive,
                           BiFunction<String, JsonObject, AgentToolResult.Control> control) implements AgentTool {
        @Override
        public String name() {
            return definition.name();
        }

        @Override
        public JsonObject declaration() {
            return definition.declaration();
        }

        @Override
        public ExecutionMode executionMode() {
            return definition.readOnly() ? ExecutionMode.PARALLEL : ExecutionMode.SEQUENTIAL;
        }

        @Override
        public AgentToolResult execute(String toolCallId, JsonObject arguments, AbortSignal signal) {
            ToolRegistry.Result result = registry.execute(
                    new ToolRegistry.Invocation(toolCallId, definition.name(), arguments, signal),
                    () -> !signal.isAborted() && (isActive == null || isActive.getAsBoolean()));
            JsonObject payload = result.payload();
            AgentToolResult outcome = AgentToolResult.of(payload);
            if (control != null && !outcome.isError()) {
                AgentToolResult.Control next = control.apply(definition.name(), payload);
                if (next != null && next != AgentToolResult.Control.NONE) {
                    outcome = outcome.withControl(next);
                }
            }
            return outcome;
        }
    }
}
