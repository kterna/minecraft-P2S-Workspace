package com.p2s.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.P2SMod;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentMessage.StopReason;
import com.p2s.agent.core.AssistantMessageEvent;
import com.p2s.agent.core.StreamFunction;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * OpenAI-compatible Chat Completions transport ({@code openai-completions}, and {@code mistral}
 * with the Mistral quirks). Never throws; refuses to return tool calls from a response that did
 * not complete. Optional parameters are only sent when configured.
 */
public final class OpenAiChatCompletions extends HttpTransport {
    private static final String[] REASONING_FIELDS = {"reasoning_content", "reasoning", "reasoning_text"};

    public OpenAiChatCompletions(Supplier<LlmRequestConfig> config) {
        super(config);
    }

    @Override
    protected HttpCall prepare(Context context, LlmRequestConfig cfg) {
        String url = cfg.apiUrl();
        if (cfg.api() == LlmApi.MISTRAL) {
            url = TransportSupport.endpoint(url.isBlank() ? "https://api.mistral.ai/v1" : url, "chat/completions", false);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + cfg.apiKey());
        return new HttpCall(url, headers, buildRequestBody(context, cfg), cfg.streaming());
    }

    @Override
    protected AgentMessage.Assistant parseStream(InputStream body, Consumer<AssistantMessageEvent> sink,
                                                 AbortSignal signal, LlmRequestConfig cfg) throws IOException {
        return withOrigin(parseStream(new InputStreamReader(body, StandardCharsets.UTF_8), sink, signal), cfg);
    }

    @Override
    protected AgentMessage.Assistant parseResponse(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg) {
        return withOrigin(parseResponse(body, sink), cfg);
    }

    private static AgentMessage.Assistant withOrigin(AgentMessage.Assistant message, LlmRequestConfig cfg) {
        if (message.failed()) {
            return message;
        }
        JsonObject data = message.replay() == null ? new JsonObject() : message.replay().data();
        return message.withReplay(TransportSupport.origin(cfg, data));
    }

    // ------------------------------------------------------------------ request

    /** Request body for the given context; tools are advertised in context order. */
    public static JsonObject buildRequestBody(Context context, String model, boolean useToolCall, boolean stream) {
        return buildRequestBody(context, new LlmRequestConfig("", "", model, 0, useToolCall, stream));
    }

    /**
     * Request body for the given context. Optional sampling fields and thinking fields are only
     * written when the configuration sets them: several providers reject parameters they do not support.
     */
    public static JsonObject buildRequestBody(Context context, LlmRequestConfig cfg) {
        ChatCompat compat = ChatCompat.resolve(cfg);
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        JsonArray messages = new JsonArray();
        requestMessages(context.systemPrompt(), context.messages(), cfg, compat).forEach(messages::add);
        body.add("messages", messages);
        if (cfg.temperature() != null && compat.supportsTemperature()) {
            body.addProperty("temperature", cfg.temperature());
        }
        Boolean topP = cfg.compatBool("supportsTopP");
        if (cfg.topP() != null && (topP == null ? compat.supportsTemperature() : topP)) {
            body.addProperty("top_p", cfg.topP());
        }
        if (cfg.maxTokens() != null) {
            body.addProperty(compat.maxTokensField(), cfg.maxTokens());
        }
        applyThinking(body, cfg, compat);
        if (cfg.streaming()) {
            body.addProperty("stream", true);
            if (compat.usageInStreaming()) {
                JsonObject options = new JsonObject();
                options.addProperty("include_usage", true);
                body.add("stream_options", options);
            }
        }
        if (cfg.useToolCall() && !context.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (TransportSupport.ToolSpec spec : TransportSupport.toolSpecs(context.tools())) {
                JsonObject function = new JsonObject();
                function.addProperty("name", spec.name());
                if (!spec.description().isBlank()) {
                    function.addProperty("description", spec.description());
                }
                function.add("parameters", spec.parameters());
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", function);
                tools.add(tool);
            }
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }
        return body;
    }

    /** Thinking fields per server dialect. Nothing is written when no level is configured. */
    private static void applyThinking(JsonObject body, LlmRequestConfig cfg, ChatCompat compat) {
        ThinkingLevel level = cfg.thinking();
        if (level == null) {
            return;
        }
        boolean on = level.enabled();
        switch (compat.thinkingFormat()) {
            case "openai" -> {
                if (on) {
                    body.addProperty("reasoning_effort", openAiEffort(level));
                }
            }
            case "deepseek" -> {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", on ? "enabled" : "disabled");
                body.add("thinking", thinking);
            }
            case "openrouter" -> {
                JsonObject reasoning = new JsonObject();
                reasoning.addProperty("effort", on ? openAiEffort(level) : "none");
                body.add("reasoning", reasoning);
            }
            case "qwen" -> body.addProperty("enable_thinking", on);
            case "mistral" -> body.addProperty("reasoning_effort", on ? "high" : "none");
            default -> {
                // "none": the server has no thinking switch, or the model name selects it.
            }
        }
    }

    static String openAiEffort(ThinkingLevel level) {
        return switch (level) {
            case OFF -> "none";
            case MINIMAL -> "minimal";
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case XHIGH, MAX -> "xhigh";
        };
    }

    static List<JsonObject> requestMessages(String systemPrompt, List<AgentMessage> messages, LlmRequestConfig cfg,
                                            ChatCompat compat) {
        Function<String, String> ids = compat.mistral() ? TransportSupport::mistralToolId : Function.identity();
        List<JsonObject> wire = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            wire.add(system);
        }
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (AgentMessage message : messages) {
            if (message instanceof AgentMessage.User user) {
                JsonObject json = new JsonObject();
                json.addProperty("role", "user");
                json.addProperty("content", user.content());
                wire.add(json);
            } else if (message instanceof AgentMessage.Assistant assistant) {
                JsonObject json = assistantMessage(assistant, cfg, compat, ids);
                assistant.toolCalls().forEach(call -> toolNames.put(call.id(), call.name()));
                if (json != null) {
                    wire.add(json);
                }
            } else if (message instanceof AgentMessage.ToolResult result) {
                JsonObject json = new JsonObject();
                json.addProperty("role", "tool");
                if (!result.toolCallId().isBlank()) {
                    json.addProperty("tool_call_id", ids.apply(result.toolCallId()));
                }
                if (compat.mistral()) {
                    json.addProperty("name", result.toolName().isBlank()
                            ? toolNames.getOrDefault(result.toolCallId(), "") : result.toolName());
                }
                json.addProperty("content", GSON.toJson(result.content()));
                wire.add(json);
            }
        }
        return wire;
    }

    private static JsonObject assistantMessage(AgentMessage.Assistant assistant, LlmRequestConfig cfg, ChatCompat compat,
                                               Function<String, String> ids) {
        JsonObject json = new JsonObject();
        json.addProperty("role", "assistant");
        JsonObject replay = TransportSupport.replay(assistant, cfg);
        boolean sameModel = replay != null;
        String thinking = assistant.thinking();
        boolean echoThinking = !thinking.isBlank() && !"never".equals(compat.echoReasoning())
                && (sameModel || (assistant.replay() == null && compat.requiresReasoningContent())
                    || "always".equals(compat.echoReasoning()));
        if (compat.mistral() && echoThinking) {
            JsonArray content = new JsonArray();
            JsonObject inner = new JsonObject();
            inner.addProperty("type", "text");
            inner.addProperty("text", thinking);
            JsonArray parts = new JsonArray();
            parts.add(inner);
            JsonObject chunk = new JsonObject();
            chunk.addProperty("type", "thinking");
            chunk.add("thinking", parts);
            content.add(chunk);
            if (!assistant.text().isBlank()) {
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", assistant.text());
                content.add(text);
            }
            json.add("content", content);
        } else if (assistant.text().isEmpty()) {
            json.add("content", JsonNull.INSTANCE);
        } else {
            json.addProperty("content", assistant.text());
        }
        if (!compat.mistral() && echoThinking) {
            String field = replay == null ? "reasoning_content" : Json.string(replay, "reasoningField");
            if (field.isBlank()) {
                field = "reasoning_content";
            }
            json.addProperty(field, thinking);
        }
        if (sameModel && replay.has("reasoningDetails") && replay.get("reasoningDetails").isJsonArray()) {
            json.add("reasoning_details", replay.get("reasoningDetails"));
        }
        if (compat.requiresReasoningContent() && !json.has("reasoning_content")) {
            json.addProperty("reasoning_content", "");
        }
        if (assistant.hasToolCalls()) {
            JsonArray calls = new JsonArray();
            for (AgentMessage.ToolCall call : assistant.toolCalls()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                // Providers may parse echoed arguments; never send back an incomplete object.
                function.addProperty("arguments", TransportSupport.argumentsText(call));
                JsonObject encoded = new JsonObject();
                encoded.addProperty("id", ids.apply(call.id()));
                encoded.addProperty("type", "function");
                encoded.add("function", function);
                calls.add(encoded);
            }
            json.add("tool_calls", calls);
        } else if (assistant.text().isBlank() && !(json.get("content") instanceof JsonArray)) {
            return null; // Providers reject assistant turns with neither content nor tool calls.
        }
        return json;
    }

    // ------------------------------------------------------------------ responses

    /** Parse an SSE response. Package-visible for protocol tests without a network. */
    static AgentMessage.Assistant parseStream(Reader reader, Consumer<AssistantMessageEvent> sink, AbortSignal signal)
            throws IOException {
        StreamState state = new StreamState(sink);
        SseDecoder.decode(reader, (event, data) -> {
            String payload = data.trim();
            if ("[DONE]".equals(payload)) {
                state.done = true;
                return false;
            }
            if (payload.isEmpty()) {
                return true;
            }
            JsonObject chunk;
            try {
                JsonElement parsed = JsonParser.parseString(payload);
                if (!parsed.isJsonObject()) {
                    return true;
                }
                chunk = parsed.getAsJsonObject();
            } catch (RuntimeException ex) {
                state.malformedChunks++;
                P2SMod.LOGGER.warn("Ignoring malformed SSE chunk: {}", truncate(payload, 200));
                return true;
            }
            if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                state.providerError = describeError(chunk.get("error"));
                return false;
            }
            state.accept(chunk);
            return true;
        });
        if (signal.isAborted()) {
            return new AgentMessage.Assistant(state.text.toString(), state.thinking.toString(), List.of(),
                    StopReason.ABORTED, "Operation aborted", System.currentTimeMillis());
        }
        return state.finish();
    }

    /** Parse a complete (non-streamed) response. */
    static AgentMessage.Assistant parseResponse(String text, Consumer<AssistantMessageEvent> sink) {
        JsonObject root = Json.parseObject(text);
        if (root == null) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response is not JSON: " + truncate(text, 200));
        }
        if (root.has("error") && !root.get("error").isJsonNull()) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, describeError(root.get("error")));
        }
        JsonArray choices = Json.array(root, "choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response has no choices");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = Json.object(choice, "message");
        if (message == null) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response has no message");
        }
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        splitContent(message.get("content"), content, thinking);
        String field = "";
        for (String key : REASONING_FIELDS) {
            if (message.has(key) && message.get(key).isJsonPrimitive() && !message.get(key).getAsString().isEmpty()) {
                thinking.append(message.get(key).getAsString());
                field = key;
                break;
            }
        }
        if (!thinking.isEmpty()) {
            sink.accept(new AssistantMessageEvent.ThinkingDelta(thinking.toString()));
        }
        if (!content.isEmpty()) {
            sink.accept(new AssistantMessageEvent.TextDelta(content.toString()));
        }
        String finish = Json.string(choice, "finish_reason");
        boolean truncated = isLength(finish);
        List<AgentMessage.ToolCall> calls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject call = element.getAsJsonObject();
                JsonObject function = Json.object(call, "function");
                if (function == null) {
                    function = new JsonObject();
                }
                AgentMessage.ToolCall decoded = ToolCallAssembler.decode(Json.string(call, "id"),
                        Json.string(function, "name"), function.get("arguments"));
                calls.add(truncated
                        ? AgentMessage.ToolCall.invalid(decoded.id(), decoded.name(), decoded.rawArguments(),
                                "the response was cut off by the output length limit")
                        : decoded);
            }
        } else if (message.has("function_call") && message.get("function_call").isJsonObject()) {
            JsonObject function = message.getAsJsonObject("function_call");
            calls.add(ToolCallAssembler.decode("", Json.string(function, "name"), function.get("arguments")));
        }
        StopReason reason = truncated ? StopReason.LENGTH : calls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
        if (calls.isEmpty() && content.toString().isBlank() && thinking.toString().isBlank()) {
            return AgentMessage.Assistant.failure(StopReason.ERROR,
                    "Model returned an empty response" + (finish.isBlank() ? "" : " (finish_reason=" + finish + ")"));
        }
        JsonObject replay = new JsonObject();
        if (!field.isEmpty()) {
            replay.addProperty("reasoningField", field);
        }
        if (message.has("reasoning_details") && message.get("reasoning_details").isJsonArray()
                && !message.getAsJsonArray("reasoning_details").isEmpty()) {
            replay.add("reasoningDetails", message.get("reasoning_details").deepCopy());
        }
        return new AgentMessage.Assistant(content.toString(), thinking.toString(), calls, reason, "",
                System.currentTimeMillis(), new AgentMessage.Replay("", "", "", replay), usage(root));
    }

    /**
     * One-shot text completion over persisted history (history compaction). Throws on failure
     * because callers fall back to keeping the uncompacted history.
     */
    public static String completeText(List<JsonObject> wireMessages, LlmRequestConfig cfg, AbortSignal signal)
            throws IOException {
        return LlmTransports.completeText(wireMessages, cfg, signal);
    }

    /** Non-streamed plain-text request over persisted history (compaction). */
    static JsonObject buildCompletionBody(List<JsonObject> wireMessages, LlmRequestConfig cfg) {
        OpenAiMessageCodec.Decoded decoded = OpenAiMessageCodec.fromWire(wireMessages);
        return buildRequestBody(new StreamFunction.Context(decoded.systemPrompt(), decoded.messages(), List.of()),
                cfg.withStreaming(false));
    }

    /** For tests: parse an SSE body held in memory. */
    static AgentMessage.Assistant parseStream(String body, Consumer<AssistantMessageEvent> sink) throws IOException {
        return parseStream(new StringReader(body), sink, AbortSignal.none());
    }

    private static boolean isLength(String finish) {
        return "length".equals(finish) || "model_length".equals(finish);
    }

    /** Mistral sends content as typed chunks; thinking chunks go to {@code thinking}. */
    private static void splitContent(JsonElement content, StringBuilder text, StringBuilder thinking) {
        if (content == null || content.isJsonNull()) {
            return;
        }
        if (!content.isJsonArray()) {
            text.append(OpenAiMessageCodec.contentText(content));
            return;
        }
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonPrimitive()) {
                text.append(part.getAsString());
            } else if (part.isJsonObject()) {
                JsonObject chunk = part.getAsJsonObject();
                if ("thinking".equals(Json.string(chunk, "type"))) {
                    thinking.append(OpenAiMessageCodec.contentText(chunk.get("thinking")));
                } else if (chunk.has("text")) {
                    text.append(OpenAiMessageCodec.contentText(chunk.get("text")));
                }
            }
        }
    }

    static AgentMessage.Usage usage(JsonObject root) {
        JsonObject usage = Json.object(root, "usage");
        if (usage == null) {
            return AgentMessage.Usage.NONE;
        }
        long prompt = Json.number(usage, "prompt_tokens");
        JsonObject promptDetails = Json.object(usage, "prompt_tokens_details");
        long cached = Math.max(Json.number(promptDetails, "cached_tokens"), Json.number(usage, "prompt_cache_hit_tokens"));
        JsonObject completionDetails = Json.object(usage, "completion_tokens_details");
        return new AgentMessage.Usage(Math.max(0, prompt - cached), Json.number(usage, "completion_tokens"), cached, 0,
                Json.number(completionDetails, "reasoning_tokens"));
    }

    private static String describeError(JsonElement error) {
        if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
            return "Provider error: " + Json.string(error.getAsJsonObject(), "message");
        }
        return "Provider error: " + truncate(error.toString(), 300);
    }



    private static final class StreamState {
        final Consumer<AssistantMessageEvent> sink;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final ToolCallAssembler tools = new ToolCallAssembler();
        final JsonArray reasoningDetails = new JsonArray();
        String reasoningField = "";
        String finishReason = "";
        String providerError = "";
        AgentMessage.Usage usage = AgentMessage.Usage.NONE;
        boolean done;
        int malformedChunks;

        StreamState(Consumer<AssistantMessageEvent> sink) {
            this.sink = sink;
        }

        void accept(JsonObject chunk) {
            if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
                usage = usage(chunk);
            }
            JsonArray choices = Json.array(chunk, "choices");
            if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
                return;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String finish = Json.string(choice, "finish_reason");
            if (!finish.isBlank()) {
                finishReason = finish;
            }
            JsonObject delta = Json.object(choice, "delta");
            if (delta == null) {
                return;
            }
            for (String key : REASONING_FIELDS) {
                if (delta.has(key) && delta.get(key).isJsonPrimitive()) {
                    String token = delta.get(key).getAsString();
                    if (!token.isEmpty()) {
                        reasoningField = reasoningField.isEmpty() ? key : reasoningField;
                        appendThinking(token);
                    }
                    break;
                }
            }
            if (delta.has("reasoning_details") && delta.get("reasoning_details").isJsonArray()) {
                for (JsonElement detail : delta.getAsJsonArray("reasoning_details")) {
                    if (detail.isJsonObject()) {
                        appendDetail(detail.getAsJsonObject());
                    }
                }
            }
            JsonElement content = delta.get("content");
            if (content != null && content.isJsonPrimitive()) {
                appendText(content.getAsString());
            } else if (content != null && content.isJsonArray()) {
                StringBuilder chunkText = new StringBuilder();
                StringBuilder chunkThinking = new StringBuilder();
                splitContent(content, chunkText, chunkThinking);
                appendThinking(chunkThinking.toString());
                appendText(chunkText.toString());
            }
            if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                int position = 0;
                for (JsonElement element : delta.getAsJsonArray("tool_calls")) {
                    if (!element.isJsonObject()) {
                        position++;
                        continue;
                    }
                    JsonObject call = element.getAsJsonObject();
                    int index = position;
                    try {
                        if (call.has("index") && !call.get("index").isJsonNull()) {
                            index = call.get("index").getAsInt();
                        }
                    } catch (RuntimeException ignored) {
                        // Keep the positional index.
                    }
                    JsonObject function = Json.object(call, "function");
                    if (function == null) {
                        function = new JsonObject();
                    }
                    String args = function.has("arguments") && function.get("arguments").isJsonPrimitive()
                            ? function.get("arguments").getAsString()
                            : function.has("arguments") && function.get("arguments").isJsonObject()
                            ? function.get("arguments").toString() : null;
                    String id = Json.string(call, "id");
                    boolean created = tools.append(index, "null".equals(id) ? "" : id, Json.string(function, "name"), args);
                    if (created) {
                        sink.accept(new AssistantMessageEvent.ToolCallStart(index, tools.idAt(index), tools.nameAt(index)));
                    }
                    if (args != null && !args.isEmpty()) {
                        sink.accept(new AssistantMessageEvent.ToolCallDelta(index, args));
                    }
                    position++;
                }
            }
        }

        private void appendText(String token) {
            if (!token.isEmpty()) {
                text.append(token);
                sink.accept(new AssistantMessageEvent.TextDelta(token));
            }
        }

        private void appendThinking(String token) {
            if (!token.isEmpty()) {
                thinking.append(token);
                sink.accept(new AssistantMessageEvent.ThinkingDelta(token));
            }
        }

        /** OpenRouter streams reasoning_details as deltas; consecutive text/summary pieces merge (as pi does). */
        private void appendDetail(JsonObject detail) {
            String type = Json.string(detail, "type");
            JsonObject last = reasoningDetails.isEmpty() ? null : reasoningDetails.get(reasoningDetails.size() - 1).getAsJsonObject();
            String key = "reasoning.text".equals(type) ? "text" : "reasoning.summary".equals(type) ? "summary" : null;
            if (key != null && last != null && type.equals(Json.string(last, "type"))) {
                // Distinct indexes/IDs are distinct reasoning items even when their types match.
                for (String field : List.of("id", "index")) {
                    if (last.has(field) && detail.has(field) && !last.get(field).equals(detail.get(field))) {
                        reasoningDetails.add(detail.deepCopy());
                        return;
                    }
                }
                for (String field : List.of("id", "format", "index")) {
                    if (Json.string(last, field).isEmpty() && detail.has(field)) {
                        last.add(field, detail.get(field).deepCopy());
                    }
                }
                last.addProperty(key, Json.string(last, key) + Json.string(detail, key));
                if (Json.string(last, "signature").isEmpty() && !Json.string(detail, "signature").isEmpty()) {
                    last.addProperty("signature", Json.string(detail, "signature"));
                }
                return;
            }
            reasoningDetails.add(detail.deepCopy());
        }

        AgentMessage.Assistant finish() {
            long now = System.currentTimeMillis();
            if (!providerError.isBlank()) {
                return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.ERROR,
                        providerError, now);
            }
            boolean completed = done || !finishReason.isBlank();
            if (!completed && !tools.isEmpty()) {
                return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.ERROR,
                        "The stream ended before the response completed; its tool calls were not executed", now);
            }
            if (!completed && text.isEmpty()) {
                return new AgentMessage.Assistant("", thinking.toString(), List.of(), StopReason.ERROR,
                        "The stream ended without a response", now);
            }
            if (!completed) {
                P2SMod.LOGGER.warn("SSE stream ended without finish_reason or [DONE]; keeping text-only response");
            }
            boolean truncated = isLength(finishReason);
            List<AgentMessage.ToolCall> calls = tools.finish(truncated);
            if (calls.isEmpty() && text.toString().isBlank() && thinking.toString().isBlank()) {
                return AgentMessage.Assistant.failure(StopReason.ERROR, "Model returned an empty response"
                        + (finishReason.isBlank() ? "" : " (finish_reason=" + finishReason + ")"));
            }
            StopReason reason = truncated ? StopReason.LENGTH : calls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
            JsonObject replay = new JsonObject();
            if (!reasoningField.isEmpty()) {
                replay.addProperty("reasoningField", reasoningField);
            }
            if (!reasoningDetails.isEmpty()) {
                replay.add("reasoningDetails", reasoningDetails);
            }
            return new AgentMessage.Assistant(text.toString(), thinking.toString(), calls, reason, "", now,
                    new AgentMessage.Replay("", "", "", replay), usage);
        }
    }
}
