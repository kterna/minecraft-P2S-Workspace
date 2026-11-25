package com.p2s.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ToolTestFixtures {
    private ToolTestFixtures() {
    }

    static JsonArray legacyDefinitions() throws Exception {
        try (var input = ToolTestFixtures.class.getResourceAsStream("/p2s/tool-catalog/legacy-tools.json")) {
            assertNotNull(input, "Missing baseline exported from the pre-refactor LLMService");
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray();
        }
    }

    static ToolRegistry.Invocation call(String id, String name) {
        return new ToolRegistry.Invocation(id, name, new JsonObject());
    }

    static JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }
}
