package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.AgentToolResult;
import com.p2s.agent.tool.ToolCatalogV2;
import com.p2s.agent.tool.ToolDefinition;
import com.p2s.agent.vfs.MemoryFs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring of the v2 tools against a fake server bridge (no game needed). */
class AgentToolsV2Test {
    /** Fake bridge: one workspace file whose staged text and revision change on stage_workspace_file. */
    private static final class FakeHost implements AgentToolsV2.Host {
        final List<String> calls = new ArrayList<>();
        final List<JsonObject> staged = new ArrayList<>();
        final Map<String, JsonElement> store = new HashMap<>();
        final boolean subagent;
        String toml = MemoryFs.sampleToml();
        String revision = "rev-1";

        FakeHost(boolean subagent) {
            this.subagent = subagent;
        }

        @Override
        public JsonObject server(String tool, JsonObject args) {
            calls.add(tool);
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", true);
            switch (tool) {
                case "get_project_state" -> {
                    JsonArray files = new JsonArray();
                    JsonObject file = new JsonObject();
                    file.addProperty("path", "workspace/house.toml");
                    files.add(file);
                    payload.add("workspace_files", files);
                    payload.add("pending_paths", new JsonArray());
                }
                case "workspace_text" -> {
                    payload.addProperty("workspace_toml", toml);
                    payload.addProperty("staged_revision", revision);
                }
                case "stage_workspace_file" -> {
                    staged.add(args.deepCopy());
                    if (args.has("base_revision") && !args.get("base_revision").getAsString().equals(revision)) {
                        payload.addProperty("ok", false);
                        payload.addProperty("error", "stale");
                        return payload;
                    }
                    toml = args.get("workspace_toml").getAsString();
                    revision = "rev-" + (staged.size() + 1);
                    payload.addProperty("staged", true);
                    payload.addProperty("staged_revision", revision);
                    payload.addProperty("changed_blocks", 3);
                }
                default -> {
                }
            }
            return payload;
        }

        @Override
        public JsonObject onClient(Supplier<JsonObject> action) {
            return action.get();
        }

        @Override
        public String sessionId() {
            return "session";
        }

        @Override
        public Set<String> skillScope() {
            return Set.of();
        }

        @Override
        public Map<String, JsonElement> scriptStore() {
            return store;
        }

        @Override
        public boolean isSubagent() {
            return subagent;
        }
    }

    private static Map<String, AgentTool> tools(FakeHost host) {
        return AgentToolsV2.create(host, null).agentTools(() -> true, null).stream()
                .collect(Collectors.toMap(AgentTool::name, tool -> tool));
    }

    private static JsonObject call(AgentTool tool, String argsJson) throws Exception {
        return tool.execute("id", com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject(), AbortSignal.create()).content();
    }

    @Test
    void mainAgentGetsTheWholeCatalogAndSubagentsNoInteractionTools() {
        Set<String> catalog = ToolCatalogV2.all().stream().map(ToolDefinition::name).collect(Collectors.toSet());
        assertEquals(catalog, tools(new FakeHost(false)).keySet());
        Set<String> sub = tools(new FakeHost(true)).keySet();
        assertFalse(sub.contains("ask_user"));
        assertFalse(sub.contains("task"));
        assertTrue(sub.contains("script"));
    }

    @Test
    void editReadsTheStagedTextAndStagesAgainstItsRevision() throws Exception {
        FakeHost host = new FakeHost(false);
        JsonObject result = call(tools(host).get("edit"),
                "{\"path\":\"workspace/house.toml\",\"edits\":[{\"old_text\":\"minecraft:oak_slab\",\"new_text\":\"minecraft:spruce_slab\"}]}");
        assertTrue(result.get("ok").getAsBoolean(), result.toString());
        assertEquals(3, result.get("changed_blocks").getAsInt());
        assertEquals("rev-1", host.staged.getFirst().get("base_revision").getAsString());
        assertTrue(host.toml.contains("minecraft:spruce_slab"));
    }

    @Test
    void readNumbersLinesAndPages() throws Exception {
        JsonObject result = call(tools(new FakeHost(false)).get("read"), "{\"path\":\"workspace/house.toml\",\"offset\":3,\"limit\":2}");
        assertEquals("     3\t[workspace]\n     4\tname = \"house\"\n", result.get("content").getAsString());
        assertTrue(result.get("truncated").getAsBoolean());
        assertEquals(5, result.get("next_offset").getAsInt());
    }

    @Test
    void scriptAndShellUseTheSameFilesAndStaging() throws Exception {
        FakeHost host = new FakeHost(false);
        Map<String, AgentTool> tools = tools(host);
        JsonObject script = call(tools.get("script"), "{\"source\":\"const d = ws.load('workspace/house.toml');"
                + " d.parts.push({name: 'ring', priority: 2, actions: geo.circle({center: [2, 6, 2], radius: 2, block: 'minecraft:stone'})});"
                + " ws.save('workspace/house.toml', d); return tools.grep({pattern: 'walls'}).count\"}");
        assertTrue(script.get("ok").getAsBoolean(), script.toString());
        assertEquals(1, host.staged.size(), "one staged write when the script succeeds");
        assertTrue(host.toml.contains("name = \"ring\""));

        JsonObject shell = call(tools.get("sh"), "{\"command\":\"grep -c ring workspace/house.toml\"}");
        assertTrue(shell.get("ok").getAsBoolean(), shell.toString());
        assertEquals("1\n", shell.get("stdout").getAsString());
    }

    @Test
    void writesOutsideTheWorkspaceAreRefused() throws Exception {
        AgentTool write = tools(new FakeHost(false)).get("write");
        JsonObject result = call(write, "{\"path\":\"skills/x/SKILL.md\",\"content\":\"x\"}");
        assertTrue(AgentToolResult.isErrorPayload(result));
    }
}
