package com.p2s.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.ClientAgentManager;
import com.p2s.SubagentManager;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.StreamFunction;
import com.p2s.agent.llm.OpenAiChatCompletions;
import com.p2s.agent.runtime.AgentRunGuard;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.p2s.agent.tool.ToolTestFixtures.legacyDefinitions;
import static com.p2s.agent.tool.ToolTestFixtures.ok;
import static org.junit.jupiter.api.Assertions.*;

class ToolWiringTest {
    @Test
    void transportAdvertisesLegacyDeclarationsInCatalogOrderAndKeepsNoToolMode() throws Exception {
        Set<String> allowed = Set.of("read_workspace_file", "propose_patch");
        // Registered in reverse on purpose: advertised order must follow the catalog.
        var registry = ToolRegistry.builder()
                .register("propose_patch", invocation -> ok())
                .register("read_workspace_file", invocation -> ok())
                .build();
        JsonArray expected = new JsonArray();
        for (var definition : legacyDefinitions()) {
            if (allowed.contains(definition.getAsJsonObject().getAsJsonObject("function").get("name").getAsString())) {
                expected.add(definition);
            }
        }
        var context = new StreamFunction.Context("system", List.of(), RegistryTools.from(registry, () -> true, null));

        JsonObject body = OpenAiChatCompletions.buildRequestBody(context, "test-model", true, false);
        assertEquals(expected, body.getAsJsonArray("tools"));
        assertEquals("auto", body.get("tool_choice").getAsString());
        assertFalse(body.has("stream"));

        JsonObject plain = OpenAiChatCompletions.buildRequestBody(context, "test-model", false, true);
        assertFalse(plain.has("tools"));
        assertFalse(plain.has("tool_choice"));
        assertTrue(plain.get("stream").getAsBoolean());
        assertEquals("test-model", plain.get("model").getAsString());
    }

    @Test
    void mainAgentAdvertisesEveryLegacyToolInOrder() throws Exception {
        ToolRegistry registry = mainRegistry();
        Set<String> advertised = ToolCatalog.all().stream().map(ToolDefinition::name).collect(Collectors.toSet());
        assertEquals(advertised, registry.toolNames());

        var context = new StreamFunction.Context("", List.of(), RegistryTools.from(registry, () -> true, null));
        JsonObject body = OpenAiChatCompletions.buildRequestBody(context, "m", true, true);
        assertEquals(legacyDefinitions(), body.getAsJsonArray("tools"));
    }

    @Test
    void onlyReadOnlyCatalogToolsMayRunInParallel() throws Exception {
        for (AgentTool tool : RegistryTools.from(mainRegistry(), () -> true, null)) {
            boolean readOnly = ToolCatalog.find(tool.name()).readOnly();
            assertEquals(readOnly ? AgentTool.ExecutionMode.PARALLEL : AgentTool.ExecutionMode.SEQUENTIAL,
                    tool.executionMode(), tool.name());
        }
    }

    @Test
    void profileAllowListLimitsAdvertisedTools() {
        var registry = ToolRegistry.builder()
                .allowOnly(List.of("read_skill"))
                .register("read_skill", invocation -> ok())
                .register("propose_patch", invocation -> ok())
                .build();
        var tools = RegistryTools.from(registry, () -> true, null);
        assertEquals(List.of("read_skill"), tools.stream().map(AgentTool::name).toList());
    }

    @Test
    void defaultProfilesOnlyAdvertiseRegisteredLegacyToolsInLegacyMode() throws Exception {
        var method = SubagentManager.class.getDeclaredMethod("createToolRegistry",
                Class.forName("com.p2s.SubagentManager$SubagentTask"), Set.class);
        method.setAccessible(true);
        var registry = (ToolRegistry) method.invoke(null, new Object[]{null, Set.of()});
        for (String profile : List.of("general-planner", "block-id-searcher", "patch-planner")) {
            try (var input = getClass().getResourceAsStream("/p2s_default_profiles/" + profile + ".json")) {
                assertNotNull(input, "Missing profile: " + profile);
                JsonObject definition = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var tool : definition.getAsJsonArray("allowed_tools")) {
                    String name = tool.getAsString();
                    if (ToolCatalog.find(name) != null) {
                        assertTrue(registry.toolNames().contains(name), profile + " advertises unimplemented legacy tool " + name);
                    } else {
                        assertNotNull(ToolCatalogV2.find(name), profile + " advertises unknown tool " + name);
                        assertFalse(registry.toolNames().contains(name), "v2-only tool exposed by legacy registry: " + name);
                    }
                }
            }
        }
    }

    private static ToolRegistry mainRegistry() throws Exception {
        var method = ClientAgentManager.class.getDeclaredMethod("createToolRegistry",
                Class.forName("com.p2s.ClientAgentManager$LocalSession"), AgentRunGuard.RunIdentity.class);
        method.setAccessible(true);
        return (ToolRegistry) method.invoke(null, new Object[]{null, null});
    }
}
