package com.p2s.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.core.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiMessageCodecTest {
    /** History in the exact format the previous harness persisted. */
    private static final String LEGACY = """
            [
              {"role":"system","content":"You are a builder."},
              {"role":"user","content":"Build a hut"},
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"read_workspace_file","arguments":"{\\"path\\":\\"a.toml\\"}"}}]},
              {"role":"tool","tool_call_id":"call_1","content":"{\\"ok\\":true,\\"tool\\":\\"read_workspace_file\\"}"},
              {"role":"assistant","content":"Done."}
            ]""";

    private static List<JsonObject> legacy() {
        List<JsonObject> messages = new ArrayList<>();
        JsonParser.parseString(LEGACY).getAsJsonArray().forEach(e -> messages.add(e.getAsJsonObject()));
        return messages;
    }

    @Test
    void legacyHistoryRoundTripsUnchanged() {
        var decoded = OpenAiMessageCodec.fromWire(legacy());
        assertEquals("You are a builder.", decoded.systemPrompt());
        assertEquals(4, decoded.messages().size());
        var result = (AgentMessage.ToolResult) decoded.messages().get(2);
        assertEquals("read_workspace_file", result.toolName());
        assertFalse(result.isError());

        assertEquals(legacy(), OpenAiMessageCodec.toWire(decoded.systemPrompt(), decoded.messages()));
    }

    @Test
    void oldFormsDecodeToValidMessages() {
        String old = """
                [
                  {"role":"user","content":[{"type":"text","text":"multi"},{"type":"text","text":"part"}]},
                  {"role":"assistant","content":"","function_call":{"name":"get_project_state","arguments":"{}"}},
                  {"role":"tool","content":"plain text result"}
                ]""";
        List<JsonObject> wire = new ArrayList<>();
        JsonParser.parseString(old).getAsJsonArray().forEach(e -> wire.add(e.getAsJsonObject()));
        var decoded = OpenAiMessageCodec.fromWire(wire);

        assertEquals("multipart", ((AgentMessage.User) decoded.messages().get(0)).content());
        var assistant = (AgentMessage.Assistant) decoded.messages().get(1);
        assertEquals("get_project_state", assistant.toolCalls().getFirst().name());
        assertFalse(assistant.toolCalls().getFirst().id().isBlank(), "Calls without ids get a synthetic id");
        var result = (AgentMessage.ToolResult) decoded.messages().get(2);
        assertEquals("plain text result", result.content().get("text").getAsString());
    }
}
