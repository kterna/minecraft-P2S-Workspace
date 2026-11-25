package com.p2s.agent.llm;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentMessage.StopReason;
import com.p2s.agent.core.AssistantMessageEvent;
import com.p2s.agent.core.StreamFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatCompletionsTest {
    private final List<AssistantMessageEvent> events = new ArrayList<>();

    private static String chunk(String deltaJson, String finish) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":" + deltaJson
                + (finish == null ? "" : ",\"finish_reason\":\"" + finish + "\"") + "}]}\r\n\r\n";
    }

    @Test
    void assemblesTextReasoningAndFragmentedToolCalls() throws Exception {
        String body = ": ping\n\n"
                + chunk("{\"reasoning_content\":\"plan \"}", null)
                + chunk("{\"content\":\"Let me \"}", null)
                + chunk("{\"content\":\"check.\"}", null)
                + chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_workspace_file\",\"arguments\":\"{\\\"pa\"}}]}", null)
                + chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"a.toml\\\"}\"}}]}", null)
                + chunk("{\"tool_calls\":[{\"index\":1,\"id\":\"call_2\",\"function\":{\"name\":\"get_project_state\",\"arguments\":\"\"}}]}", null)
                + chunk("{}", "tool_calls")
                + "data: [DONE]\n\n";
        AgentMessage.Assistant message = OpenAiChatCompletions.parseStream(body, events::add);

        assertEquals(StopReason.TOOL_USE, message.stopReason());
        assertEquals("Let me check.", message.text());
        assertEquals("plan ", message.thinking());
        assertEquals(2, message.toolCalls().size());
        AgentMessage.ToolCall first = message.toolCalls().get(0);
        assertEquals("call_1", first.id());
        assertTrue(first.hasValidArguments());
        assertEquals("a.toml", first.arguments().get("path").getAsString());
        assertTrue(message.toolCalls().get(1).hasValidArguments(), "Empty arguments mean {}");
        assertTrue(events.stream().anyMatch(e -> e instanceof AssistantMessageEvent.ThinkingDelta));
        assertEquals(2, events.stream().filter(e -> e instanceof AssistantMessageEvent.ToolCallStart).count());
    }

    @Test
    void streamCutBeforeCompletionNeverReturnsToolCalls() throws Exception {
        String body = chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"propose_patch\",\"arguments\":\"{\\\"path\\\":\\\"a\"}}]}", null);
        AgentMessage.Assistant message = OpenAiChatCompletions.parseStream(body, events::add);
        assertEquals(StopReason.ERROR, message.stopReason());
        assertTrue(message.toolCalls().isEmpty());
        assertFalse(message.errorMessage().isBlank());
    }

    @Test
    void textOnlyStreamWithoutTerminatorIsKept() throws Exception {
        AgentMessage.Assistant message = OpenAiChatCompletions.parseStream(chunk("{\"content\":\"hi\"}", null), events::add);
        assertEquals(StopReason.STOP, message.stopReason());
        assertEquals("hi", message.text());
    }

    @Test
    void lengthLimitedToolCallsAreMarkedInvalidNotRepaired() throws Exception {
        String body = chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"propose_patch\",\"arguments\":\"{}\"}}]}", "length");
        AgentMessage.Assistant message = OpenAiChatCompletions.parseStream(body, events::add);
        assertEquals(StopReason.LENGTH, message.stopReason());
        assertFalse(message.toolCalls().getFirst().hasValidArguments());
    }

    @Test
    void malformedArgumentJsonIsInvalid() throws Exception {
        String body = chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"propose_patch\",\"arguments\":\"{\\\"path\\\": }\"}}]}", "tool_calls");
        AgentMessage.ToolCall call = OpenAiChatCompletions.parseStream(body, events::add).toolCalls().getFirst();
        assertFalse(call.hasValidArguments());
        assertNull(call.arguments());
    }

    @Test
    void providerErrorChunkAndMalformedChunks() throws Exception {
        String body = "data: {not json\n\n" + chunk("{\"content\":\"x\"}", null)
                + "data: {\"error\":{\"message\":\"rate limited\"}}\n\n";
        AgentMessage.Assistant message = OpenAiChatCompletions.parseStream(body, events::add);
        assertEquals(StopReason.ERROR, message.stopReason());
        assertTrue(message.errorMessage().contains("rate limited"));
    }

    @Test
    void parsesNonStreamingResponses() {
        String withTools = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"read_skill\",\"arguments\":{\"id\":\"x\"}}}]}}]}";
        AgentMessage.Assistant message = OpenAiChatCompletions.parseResponse(withTools, events::add);
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        assertEquals("x", message.toolCalls().getFirst().arguments().get("id").getAsString());

        AgentMessage.Assistant empty = OpenAiChatCompletions.parseResponse(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}", events::add);
        assertEquals(StopReason.ERROR, empty.stopReason());
        assertEquals(StopReason.ERROR, OpenAiChatCompletions.parseResponse("<html>", events::add).stopReason());
    }

    @Test
    void requestBodyNeverEchoesBrokenArguments() {
        var assistant = new AgentMessage.Assistant("", "", List.of(
                AgentMessage.ToolCall.invalid("c", "propose_patch", "{\"path\":", "incomplete")),
                StopReason.TOOL_USE, "", 0L);
        var result = new AgentMessage.ToolResult("c", "propose_patch", new JsonObject(), true, 0L);
        JsonObject body = OpenAiChatCompletions.buildRequestBody(
                new StreamFunction.Context("sys", List.of(AgentMessage.User.of("hi"), assistant, result), List.of()),
                "m", true, true);
        var messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        String echoed = messages.get(2).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .getAsJsonObject("function").get("arguments").getAsString();
        assertEquals("{}", echoed);
        assertFalse(body.has("tools"), "No tools advertised when the context has none");
    }
}
