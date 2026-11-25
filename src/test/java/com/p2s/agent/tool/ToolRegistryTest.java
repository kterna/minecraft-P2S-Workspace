package com.p2s.agent.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.p2s.agent.tool.ToolTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void unadvertisedAndProfileDeniedToolsCannotInvokeHandlers() {
        AtomicInteger executed = new AtomicInteger();
        ToolRegistry registry = ToolRegistry.builder()
                .register("read_workspace_file", invocation -> { executed.incrementAndGet(); return ok(); })
                .register("delete_workspace_file", invocation -> { executed.incrementAndGet(); return ok(); })
                .allowOnly(Set.of("read_workspace_file"))
                .build();
        var denied = registry.execute(call("denied", "delete_workspace_file"), () -> true);
        var unknown = registry.execute(call("unknown", "not_a_tool"), () -> true);
        assertEquals("tool_not_allowed", denied.payload().get("error_code").getAsString());
        assertEquals("unknown_tool", unknown.payload().get("error_code").getAsString());
        assertEquals("denied", denied.invocation().id());
        assertEquals(0, executed.get());
    }

    @Test
    void registryOwnsItsAllowListAndRegisteredHandlers() {
        List<String> allowed = new ArrayList<>(List.of("read_workspace_file"));
        var builder = ToolRegistry.builder()
                .register("read_workspace_file", invocation -> ok())
                .register("delete_workspace_file", invocation -> fail("Permission was widened"))
                .allowOnly(allowed);
        var registry = builder.build();
        allowed.add("delete_workspace_file");
        builder.allowOnly(allowed).register("update_plan", invocation -> ok());
        assertFalse(registry.execute(call("id", "delete_workspace_file"), () -> true).payload().get("ok").getAsBoolean());
        assertFalse(registry.toolNames().contains("update_plan"));
        assertThrows(UnsupportedOperationException.class, () -> registry.toolNames().clear());
    }

    @Test
    void duplicateOrUndeclaredRegistrationsFailDuringSetup() {
        var builder = ToolRegistry.builder().register("read_skill", invocation -> ok());
        assertThrows(IllegalArgumentException.class, () -> builder.register("read_skill", invocation -> ok()));
        assertThrows(IllegalArgumentException.class, () -> builder.register("not_a_tool", invocation -> ok()));
    }

    @Test
    void argumentsAndResultsAreSnapshotsAcrossThreads() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "original.toml");
        var invocation = new ToolRegistry.Invocation("id", "read_workspace_file", args);
        args.addProperty("path", "changed.toml");
        invocation.arguments().getAsJsonObject().addProperty("path", "changed-again.toml");
        assertEquals("original.toml", invocation.arguments().getAsJsonObject().get("path").getAsString());
        JsonObject output = ok();
        var result = new ToolRegistry.Result(invocation, output);
        output.addProperty("ok", false);
        result.payload().addProperty("ok", false);
        assertTrue(result.payload().get("ok").getAsBoolean());
    }

    @Test
    void inactiveRunsReturnAnObservationWithoutExecutingTheTool() {
        var registry = ToolRegistry.builder()
                .register("create_workspace_file", invocation -> fail("Inactive run executed a write"))
                .build();
        var result = registry.execute(call("id", "create_workspace_file"), () -> false);
        assertEquals("cancelled", result.payload().get("error_code").getAsString());
    }

    @Test
    void exceptionsAndMissingResultsRemainAssociatedWithTheirCallIds() {
        var registry = ToolRegistry.builder()
                .register("read_skill", invocation -> { throw new IllegalStateException("read failed"); })
                .register("read_subdoc", invocation -> null)
                .build();
        var failed = registry.execute(call("failed", "read_skill"), () -> true);
        var empty = registry.execute(call("empty", "read_subdoc"), () -> true);
        assertEquals("failed", failed.invocation().id());
        assertEquals("read failed", failed.payload().get("error").getAsString());
        assertEquals("execution_failed", failed.payload().get("error_code").getAsString());
        assertEquals("empty", empty.invocation().id());
        assertEquals("empty_result", empty.payload().get("error_code").getAsString());
    }
}
