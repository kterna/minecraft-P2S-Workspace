package com.p2s.agent.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Explicit handlers bound to one run; model-supplied names never select arbitrary code. */
public final class ToolRegistry {
    private final Map<String, Handler> handlers;
    private final Set<String> allowedTools;
    private final Function<String, ToolDefinition> catalog;

    private ToolRegistry(Map<String, Handler> handlers, Set<String> allowedTools, Function<String, ToolDefinition> catalog) {
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
        this.allowedTools = allowedTools == null ? null : Set.copyOf(allowedTools);
        this.catalog = catalog;
    }

    /** Registry over the legacy catalog. */
    public static Builder builder() {
        return new Builder(ToolCatalog::find);
    }

    /** Registry over another catalog, e.g. {@link ToolCatalogV2#find}. */
    public static Builder builder(Function<String, ToolDefinition> catalog) {
        return new Builder(catalog);
    }

    public ToolDefinition definition(String name) {
        return catalog.apply(name);
    }

    public Set<String> toolNames() {
        return handlers.keySet();
    }

    /** True when the tool has a handler and the profile allow-list (if any) permits it. */
    public boolean isAvailable(String name) {
        return handlers.containsKey(name) && (allowedTools == null || allowedTools.contains(name));
    }

    public boolean isParallelRead(Invocation invocation) {
        ToolDefinition definition = catalog.apply(invocation.name());
        return definition != null && definition.readOnly() && handlers.containsKey(invocation.name())
                && (allowedTools == null || allowedTools.contains(invocation.name()));
    }

    public Result execute(Invocation invocation, BooleanSupplier isActive) {
        try {
            if (!isActive.getAsBoolean()) {
                return Result.failure(invocation, "cancelled", "Agent run is no longer active");
            }
            Handler handler = handlers.get(invocation.name());
            if (handler == null) {
                return Result.failure(invocation, "unknown_tool", "Unknown tool: " + invocation.name());
            }
            if (allowedTools != null && !allowedTools.contains(invocation.name())) {
                return Result.failure(invocation, "tool_not_allowed", "Tool not allowed by profile: " + invocation.name());
            }
            JsonObject payload = handler.execute(invocation);
            return payload == null
                    ? Result.failure(invocation, "empty_result", "Tool returned no result")
                    : new Result(invocation, payload);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.failure(invocation, "interrupted", "Tool execution was interrupted");
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return Result.failure(invocation, "execution_failed", message);
        }
    }

    @FunctionalInterface
    public interface Handler {
        JsonObject execute(Invocation invocation) throws Exception;
    }

    public record Invocation(String id, String name, JsonElement arguments, com.p2s.agent.core.AbortSignal signal) {
        public Invocation {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            arguments = arguments == null ? JsonNull.INSTANCE : arguments.deepCopy();
            signal = signal == null ? com.p2s.agent.core.AbortSignal.none() : signal;
        }

        public Invocation(String id, String name, JsonElement arguments) {
            this(id, name, arguments, null);
        }

        @Override
        public JsonElement arguments() {
            return arguments.deepCopy();
        }
    }

    public record Result(Invocation invocation, JsonObject payload) {
        public Result {
            payload = payload.deepCopy();
        }

        @Override
        public JsonObject payload() {
            return payload.deepCopy();
        }

        public static Result failure(Invocation invocation, String code, String message) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("tool", invocation.name());
            payload.addProperty("error", message);
            payload.addProperty("error_code", code);
            return new Result(invocation, payload);
        }
    }

    public static final class Builder {
        private final Map<String, Handler> handlers = new LinkedHashMap<>();
        private final Function<String, ToolDefinition> catalog;
        private Set<String> allowedTools;

        private Builder(Function<String, ToolDefinition> catalog) {
            this.catalog = catalog;
        }

        public Builder register(String name, Handler handler) {
            if (catalog.apply(name) == null) {
                throw new IllegalArgumentException("Tool is missing from the catalog: " + name);
            }
            Objects.requireNonNull(handler, "handler");
            if (handlers.putIfAbsent(name, handler) != null) {
                throw new IllegalArgumentException("Duplicate tool handler: " + name);
            }
            return this;
        }

        public Builder allowOnly(Collection<String> names) {
            allowedTools = ToolCatalog.normalizeAllowedNames(names);
            return this;
        }

        public ToolRegistry build() {
            return new ToolRegistry(handlers, allowedTools, catalog);
        }
    }
}
