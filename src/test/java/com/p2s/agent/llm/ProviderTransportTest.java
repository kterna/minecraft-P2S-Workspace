package com.p2s.agent.llm;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.AgentToolResult;
import com.p2s.agent.core.StreamFunction;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderTransportTest {
    private static final AgentTool READ = new AgentTool() {
        public String name() { return "read"; }
        public JsonObject declaration() { return Json.parseObject("""
                {"type":"function","function":{"name":"read","description":"Read a block","parameters":{"type":"object","properties":{}}}}
                """); }
        public AgentToolResult execute(String id, JsonObject args, AbortSignal signal) { throw new AssertionError(); }
    };
    static final StreamFunction.Context CONTEXT = new StreamFunction.Context("sys", List.of(AgentMessage.User.of("hi")), List.of(READ));

    static LlmRequestConfig config(LlmApi api) {
        return new LlmRequestConfig.Builder().api(api).apiUrl("https://example.invalid/v1").apiKey("fixture")
                .model("demo").providerOptions(Json.parseObject("{\"region\":\"us-east-1\"}")).build();
    }

    private static AgentMessage.Assistant stream(HttpTransport transport, String... events) throws Exception {
        StringBuilder body = new StringBuilder();
        for (String event : events) body.append("data: ").append(event).append("\n\n");
        LlmApi api = transport instanceof OpenAiResponses ? LlmApi.OPENAI_RESPONSES
                : transport instanceof AnthropicMessages ? LlmApi.ANTHROPIC_MESSAGES : LlmApi.GOOGLE_GENERATIVE_AI;
        return transport.parseStream(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
                event -> {}, AbortSignal.none(), config(api));
    }

    private static StreamFunction.Context savedHistory(AgentMessage.Assistant message) {
        assertEquals(AgentMessage.StopReason.TOOL_USE, message.stopReason(), message.errorMessage());
        var result = new AgentMessage.ToolResult(message.toolCalls().getFirst().id(), "read", new JsonObject(), false, 0);
        var wire = OpenAiMessageCodec.toWire("sys", List.of(AgentMessage.User.of("hi"), message, result));
        var decoded = OpenAiMessageCodec.fromWire(wire);
        return new StreamFunction.Context(decoded.systemPrompt(), decoded.messages(), List.of(READ));
    }

    @Test
    void responsesWrapsToolsAndRestoresEncryptedItems() throws Exception {
        var cfg = config(LlmApi.OPENAI_RESPONSES);
        var api = LlmTransports.create(cfg);
        String output = """
                {"status":"completed","output":[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"sealed"},
                {"type":"function_call","id":"fc_1","call_id":"abc","name":"read","arguments":"{}"}]}
                """.strip().replace("\n", "");
        var nonStream = api.parseResponse(output, ignored -> {}, cfg);
        var response = stream(api, "{\"type\":\"response.completed\",\"response\":" + output + "}");
        assertEquals(nonStream.toolCalls(), response.toolCalls());
        JsonObject body = api.requestBody(savedHistory(response), cfg);
        assertFalse(body.get("store").getAsBoolean());
        assertEquals("reasoning.encrypted_content", body.getAsJsonArray("include").get(0).getAsString());
        assertEquals("read", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("sealed", body.getAsJsonArray("input").get(1).getAsJsonObject().get("encrypted_content").getAsString());
        assertEquals("function_call_output", body.getAsJsonArray("input").get(3).getAsJsonObject().get("type").getAsString());
        assertFalse(api.requestBody(savedHistory(response), cfg.toBuilder().model("other").build()).toString().contains("sealed"));
        assertFalse(stream(api, "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"name\":\"read\"}}").hasToolCalls());
    }

    @Test
    void anthropicReplaysSignedThinkingAndToolResultAfterStreaming() throws Exception {
        var cfg = config(LlmApi.ANTHROPIC_MESSAGES);
        var api = LlmTransports.create(cfg);
        var response = stream(api,
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"plan\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sealed\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c\",\"name\":\"read\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}", "{\"type\":\"message_stop\"}");
        assertEquals("plan", response.thinking());
        var body = api.requestBody(savedHistory(response), cfg);
        assertEquals(8192, body.get("max_tokens").getAsInt());
        assertEquals("sealed", body.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content")
                .get(0).getAsJsonObject().get("signature").getAsString());
        assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));
        assertTrue(body.getAsJsonArray("messages").get(2).toString().contains("tool_result"));
        assertFalse(body.has("temperature"));
        assertTrue(api.parseResponse("{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}", ignored -> {}, cfg).failed());
    }

    @Test
    void geminiAndVertexUseNativeMethodsAndRestoreSignedParts() throws Exception {
        var cfg = config(LlmApi.GOOGLE_GENERATIVE_AI);
        var api = LlmTransports.create(cfg);
        var prepared = api.prepareWithExtras(CONTEXT, cfg);
        assertEquals("https://example.invalid/v1/models/demo:streamGenerateContent?alt=sse", prepared.url());
        assertEquals("fixture", prepared.headers().get("x-goog-api-key"));
        assertTrue(prepared.body().getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonArray("functionDeclarations")
                .get(0).getAsJsonObject().has("parametersJsonSchema"));
        assertFalse(prepared.body().has("generationConfig"));
        String candidate = """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"think","thought":true},{"functionCall":{"id":"c","name":"read","args":{}},"thoughtSignature":"sealed"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10}}
                """.strip();
        var response = stream(api, candidate);
        assertEquals(10, response.usage().input());
        assertEquals("think", response.thinking());
        var history = savedHistory(response);
        var body = api.requestBody(history, cfg);
        var turns = body.getAsJsonArray("contents");
        assertEquals("sealed", turns.get(1).getAsJsonObject().getAsJsonArray("parts").get(1).getAsJsonObject().get("thoughtSignature").getAsString());
        assertEquals("c", turns.get(2).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("functionResponse").get("id").getAsString());
        assertFalse(api.requestBody(history, cfg.toBuilder().provider("elsewhere").build()).toString().contains("sealed"));
        var vertex = config(LlmApi.GOOGLE_VERTEX).withStreaming(false);
        var call = LlmTransports.create(vertex).prepareWithExtras(CONTEXT, vertex);
        assertEquals("Bearer fixture", call.headers().get("Authorization"));
        assertTrue(call.url().endsWith("/models/demo:generateContent"));
        assertEquals(response.toolCalls(), api.parseResponse(candidate, ignored -> {}, cfg).toolCalls());
        assertTrue(stream(api, candidate.replace("\"STOP\"", "\"MAX_TOKENS\"")).failed());
        assertTrue(stream(api, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}").failed());
    }

    @Test
    void mistralChatPairsNineCharacterIdsAndSeparatesThinkingChunks() throws Exception {
        var cfg = config(LlmApi.MISTRAL);
        var api = LlmTransports.create(cfg);
        String delta = """
                {"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"plan"}]}],"tool_calls":[{"index":0,"id":"long-tool-id","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                """.strip();
        var response = api.parseStream(new ByteArrayInputStream(("data: " + delta + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)),
                event -> {}, AbortSignal.none(), cfg);
        assertEquals("plan", response.thinking());
        var messages = api.requestBody(savedHistory(response), cfg).getAsJsonArray("messages");
        String id = messages.get(2).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString();
        assertTrue(id.matches("[a-zA-Z0-9]{9}"));
        assertEquals(id, messages.get(3).getAsJsonObject().get("tool_call_id").getAsString());
        assertEquals("read", messages.get(3).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void endpointQueriesAndParallelLegacyIdsSurviveProtocolSwitch() {
        assertEquals("https://example.invalid/v1/messages?route=a", AnthropicMessages.endpoint("https://example.invalid?route=a"));
        assertEquals("https://example.invalid/v1/responses?route=a", OpenAiResponses.endpoint("https://example.invalid/v1?route=a"));
        var one = AgentMessage.ToolCall.valid("a:b", "read", "{}", new JsonObject());
        var two = AgentMessage.ToolCall.valid("a/b", "read", "{}", new JsonObject());
        var assistant = new AgentMessage.Assistant("", "", List.of(one, two), AgentMessage.StopReason.TOOL_USE, "", 0L);
        var history = new StreamFunction.Context("", List.of(assistant,
                new AgentMessage.ToolResult(one.id(), "read", new JsonObject(), false, 1L),
                new AgentMessage.ToolResult(two.id(), "read", new JsonObject(), false, 2L)), List.of(READ));
        var cfg = config(LlmApi.GOOGLE_GENERATIVE_AI).toBuilder().model("gemini-3-pro").build();
        var turns = LlmTransports.create(cfg).requestBody(history, cfg).getAsJsonArray("contents");
        var calls = turns.get(0).getAsJsonObject().getAsJsonArray("parts");
        var results = turns.get(1).getAsJsonObject().getAsJsonArray("parts");
        assertNotEquals(TransportSupport.safeToolId(one.id()), TransportSupport.safeToolId(two.id()));
        for (int i = 0; i < 2; i++) {
            String callId = calls.get(i).getAsJsonObject().getAsJsonObject("functionCall").get("id").getAsString();
            assertEquals(callId, results.get(i).getAsJsonObject().getAsJsonObject("functionResponse").get("id").getAsString());
            assertEquals(TransportSupport.safeToolId(assistant.toolCalls().get(i).id()), callId);
        }
    }

    @Test
    void claudeAdaptiveEffortFollowsModelSupport() {
        var cfg = config(LlmApi.ANTHROPIC_MESSAGES).toBuilder().model("claude-opus-4-6").thinking(ThinkingLevel.XHIGH).build();
        assertEquals("high", AnthropicMessages.buildRequestBody(CONTEXT, cfg).getAsJsonObject("output_config").get("effort").getAsString());
        var newer = cfg.toBuilder().model("claude-opus-4-7").build();
        assertEquals("xhigh", AnthropicMessages.buildRequestBody(CONTEXT, newer).getAsJsonObject("output_config").get("effort").getAsString());
    }

    @Test
    void claudeGatewayPresetOmitsSamplingAndOpenRouterKeepsLateReasoningMetadata() throws Exception {
        var cfg = config(LlmApi.OPENAI_COMPLETIONS).toBuilder().temperature(0.4).topP(0.9)
                .compat(Json.parseObject("{\"preset\":\"anthropic-via-gateway\"}")).build();
        var body = OpenAiChatCompletions.buildRequestBody(CONTEXT, cfg);
        assertFalse(body.has("temperature"));
        assertFalse(body.has("top_p"));
        String stream = """
                data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"a"}]}}]}

                data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"b","id":"r","index":1,"format":"x"}],"content":"ok"},"finish_reason":"stop"}]}

                """;
        var response = OpenAiChatCompletions.parseStream(stream, event -> {});
        var detail = response.replay().data().getAsJsonArray("reasoningDetails").get(0).getAsJsonObject();
        assertEquals("ab", Json.string(detail, "text"));
        assertEquals("r", Json.string(detail, "id"));
        assertEquals(1, Json.number(detail, "index"));
        assertEquals("x", Json.string(detail, "format"));
    }

    @Test
    void compactionPreservesToolNamesAsTextForNativeProtocols() {
        var call = AgentMessage.ToolCall.valid("c", "read", "{}", new JsonObject());
        var assistant = new AgentMessage.Assistant("", "", List.of(call), AgentMessage.StopReason.TOOL_USE, "", 0);
        var history = savedHistory(assistant);
        var wire = OpenAiMessageCodec.toWire(history.systemPrompt(), history.messages());
        var nativeCfg = config(LlmApi.ANTHROPIC_MESSAGES);
        var flat = LlmTransports.completionContext(wire, nativeCfg);
        assertTrue(flat.messages().stream().noneMatch(m -> m instanceof AgentMessage.ToolResult));
        assertTrue(flat.messages().toString().contains("[tool call read]"));
        assertTrue(flat.tools().isEmpty());
    }
}
