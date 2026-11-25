package com.p2s.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.p2s.agent.tool.ToolTestFixtures.legacyDefinitions;
import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {
    @Test
    void preservesEveryPreviouslyAdvertisedSchemaAndItsOrder() throws Exception {
        assertEquals(legacyDefinitions(), ToolCatalog.definitions(true, null));
    }

    @Test
    void legacyNonClientModeKeepsOnlyItsSevenServerTools() throws Exception {
        Set<String> serverTools = Set.of("get_project_state", "read_workspace_file", "create_workspace_file",
                "rename_workspace_file", "delete_workspace_file", "search_block_ids", "propose_patch");
        JsonArray expected = new JsonArray();
        for (var definition : legacyDefinitions()) {
            if (serverTools.contains(definition.getAsJsonObject().getAsJsonObject("function").get("name").getAsString())) {
                expected.add(definition);
            }
        }
        assertEquals(7, expected.size());
        assertEquals(expected, ToolCatalog.definitions(false, null));
    }

    @Test
    void emptyOrUnknownAllowListsNeverBecomeUnrestricted() {
        assertTrue(ToolCatalog.definitions(true, List.of()).isEmpty());
        assertTrue(ToolCatalog.definitions(true, List.of("unknown_tool")).isEmpty());
        assertTrue(ToolCatalog.definitions(false, List.of("read_skill")).isEmpty());
        JsonArray selected = ToolCatalog.definitions(true,
                Arrays.asList(null, "", "  read_skill  ", "unknown_tool"));
        assertEquals(1, selected.size());
        assertEquals("read_skill", selected.get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
    }

    @Test
    void allowListOrderDoesNotReorderToolDeclarations() {
        JsonArray selected = ToolCatalog.definitions(true, List.of("propose_patch", "get_project_state"));
        assertEquals("get_project_state", selected.get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        assertEquals("propose_patch", selected.get(1).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
    }

    @Test
    void callersCannotChangeDefinitionsUsedByLaterRequests() throws Exception {
        JsonObject exposed = ToolCatalog.find("propose_patch").declaration();
        exposed.getAsJsonObject("function").addProperty("name", "broken");
        exposed.getAsJsonObject("function").getAsJsonObject("parameters").remove("required");
        assertThrows(UnsupportedOperationException.class, () -> ToolCatalog.all().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ToolCatalog.find("propose_patch").effects().clear());
        assertEquals(legacyDefinitions(), ToolCatalog.definitions(true, null));
    }

    @Test
    void planAndApprovalToolsAreSchedulingBarriers() {
        assertTrue(ToolCatalog.find("get_project_state").readOnly());
        assertTrue(ToolCatalog.find("read_workspace_file").readOnly());
        assertFalse(ToolCatalog.find("update_plan").readOnly());
        assertTrue(ToolCatalog.find("update_plan").effects().contains(ToolDefinition.Effect.SESSION_STATE));
        assertEquals(Set.of(ToolDefinition.Effect.PROJECT_STATE, ToolDefinition.Effect.INTERACTION),
                ToolCatalog.find("propose_patch").effects());
        assertFalse(ToolCatalog.find("request_user_choice").readOnly());
    }
}
