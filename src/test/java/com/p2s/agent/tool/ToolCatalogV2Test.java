package com.p2s.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogV2Test {
    @AfterEach
    void clearExtensions() {
        ToolExtensions.clear();
    }

    @Test
    void declarationsAreWellFormedAndUnique() {
        Set<String> names = new HashSet<>();
        for (ToolDefinition tool : ToolCatalogV2.all()) {
            assertTrue(names.add(tool.name()), "duplicate " + tool.name());
            JsonObject function = tool.declaration().getAsJsonObject("function");
            assertEquals(tool.name(), function.get("name").getAsString());
            assertFalse(function.get("description").getAsString().isBlank(), tool.name());
            assertEquals("object", function.getAsJsonObject("parameters").get("type").getAsString(), tool.name());
        }
        assertTrue(names.containsAll(List.of("ls", "read", "grep", "find", "edit", "write", "set_part", "script", "sh",
                "inspect_world", "ask_user", "task")));
    }

    @Test
    void everyLegacyToolMapsToExistingV2Tools() {
        for (ToolDefinition legacy : ToolCatalog.all()) {
            List<String> replacements = ToolCatalogV2.fromLegacy(legacy.name());
            assertFalse(replacements.isEmpty(), legacy.name());
            for (String name : replacements) {
                assertNotNull(ToolCatalogV2.find(name), legacy.name() + " maps to unknown " + name);
            }
        }
    }

    @Test
    void writingToolsAreBarriersAndReadsMayRunInParallel() {
        for (String read : List.of("ls", "read", "grep", "find", "project_info", "inspect_world", "task_status")) {
            assertTrue(ToolCatalogV2.find(read).readOnly(), read);
        }
        for (String write : List.of("edit", "write", "set_part", "move", "delete", "script", "sh", "ask_user", "update_plan")) {
            assertFalse(ToolCatalogV2.find(write).readOnly(), write);
        }
        assertTrue(ToolCatalogV2.NOT_CALLABLE_FROM_SCRIPTS.containsAll(List.of("script", "sh", "ask_user")));
    }

    @Test
    void extensionsJoinTheCatalogButCannotShadowBuiltins() {
        JsonObject declaration = JsonParser.parseString(
                "{\"type\":\"function\",\"function\":{\"name\":\"measure_room\",\"description\":\"d\",\"parameters\":{\"type\":\"object\"}}}")
                .getAsJsonObject();
        ToolExtensions.register("test-mod", declaration, Set.of(), (context, args) -> new JsonObject());
        assertNotNull(ToolCatalogV2.find("measure_room"));
        assertEquals("measure_room", ToolCatalogV2.all().getLast().name());
        assertThrows(IllegalArgumentException.class, () -> ToolExtensions.register("test-mod", declaration, Set.of(),
                (context, args) -> new JsonObject()));
        JsonObject shadow = JsonParser.parseString(
                "{\"type\":\"function\",\"function\":{\"name\":\"read\",\"description\":\"d\"}}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> ToolExtensions.register("test-mod", shadow, Set.of(),
                (context, args) -> new JsonObject()));
    }

    @Test
    void legacyCatalogIsUnchanged() throws Exception {
        assertEquals(ToolTestFixtures.legacyDefinitions(), ToolCatalog.definitions(true, null));
    }
}
