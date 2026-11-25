package com.p2s.agent.tool;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Set;

/** Tool metadata shared by protocol generation and execution scheduling. */
public record ToolDefinition(String name, boolean clientTool, Set<Effect> effects, JsonObject declaration) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        effects = Set.copyOf(effects);
        declaration = declaration.deepCopy();
        if (!name.equals(declaration.getAsJsonObject("function").get("name").getAsString())) {
            throw new IllegalArgumentException("Tool declaration name does not match " + name);
        }
    }

    @Override
    public JsonObject declaration() {
        return declaration.deepCopy();
    }

    public boolean readOnly() {
        return effects.isEmpty();
    }

    public enum Effect {
        SESSION_STATE,
        PROJECT_STATE,
        INTERACTION,
        SUBAGENT_STATE
    }
}
