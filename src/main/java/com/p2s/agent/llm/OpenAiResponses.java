package com.p2s.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.P2SMod;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentMessage.StopReason;
import com.p2s.agent.core.AssistantMessageEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * OpenAI Responses ({@code POST /v1/responses}), stateless: {@code store:false}, no
 * {@code previous_response_id}. Reasoning items with {@code encrypted_content} are kept as
 * replay data and sent back to the same model, as OpenAI documents for stateless tool loops.
 */
public final class OpenAiResponses extends HttpTransport {
    static final String DEFAULT_URL = "https://api.openai.com/v1/responses";

    public OpenAiResponses(Supplier<LlmRequestConfig> config) {
        super(config);
    }

    @Override
    protected HttpCall prepare(Context context, LlmRequestConfig cfg) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + cfg.apiKey());
        return new HttpCall(endpoint(cfg.apiUrl()), headers, buildRequestBody(context, cfg), cfg.streaming());
    }

    static String endpoint(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_URL;
        }
        return TransportSupport.endpoint(url, "responses", false);
    }

    // ------------------------------------------------------------------ request

    public static JsonObject buildRequestBody(Context context, LlmRequestConfig cfg) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        if (!context.systemPrompt().isBlank()) {
            body.addProperty("instructions", context.systemPrompt());
        }
        body.add("input", convertInput(context.messages(), cfg));
        body.addProperty("store", false);
        if (cfg.maxTokens() != null) {
            body.addProperty("max_output_tokens", Math.max(16, cfg.maxTokens()));
        }
        ThinkingLevel level = cfg.thinking();
        boolean thinking = level != null && level.enabled();
        Boolean supportsTemperature = cfg.compatBool("supportsTemperature");
        boolean sampling = supportsTemperature != null ? supportsTemperature : !thinking;
        if (cfg.temperature() != null && sampling) {
            body.addProperty("temperature", cfg.temperature());
        }
        if (cfg.topP() != null && sampling) {
            body.addProperty("top_p", cfg.topP());
        }
        // Models can reason by default even when the player has chosen no effort value.
        if (level != ThinkingLevel.OFF) {
            JsonArray include = new JsonArray();
            include.add("reasoning.encrypted_content");
            body.add("include", include);
        }
        if (level != null) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", OpenAiChatCompletions.openAiEffort(level));
            if (thinking) {
                String summary = cfg.compatString("reasoningSummary");
                if (!"none".equalsIgnoreCase(summary)) {
                    reasoning.addProperty("summary", summary == null ? "auto" : summary);
                }
            }
            body.add("reasoning", reasoning);
        }
        if (cfg.useToolCall() && !context.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (TransportSupport.ToolSpec spec : TransportSupport.toolSpecs(context.tools())) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.addProperty("name", spec.name());
                if (!spec.description().isBlank()) {
                    tool.addProperty("description", spec.description());
                }
                tool.add("parameters", spec.parameters());
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        if (cfg.streaming()) {
            body.addProperty("stream", true);
        }
        return body;
    }

    static JsonArray convertInput(List<AgentMessage> messages, LlmRequestConfig cfg) {
        JsonArray input = new JsonArray();
        for (AgentMessage message : messages) {
            if (message instanceof AgentMessage.User user) {
                JsonObject item = new JsonObject();
                item.addProperty("role", "user");
                item.addProperty("content", user.content());
                input.add(item);
            } else if (message instanceof AgentMessage.ToolResult result) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "function_call_output");
                item.addProperty("call_id", TransportSupport.safeToolId(result.toolCallId()));
                item.addProperty("output", GSON.toJson(result.content()));
                input.add(item);
            } else if (message instanceof AgentMessage.Assistant assistant) {
                assistantItems(assistant, cfg).forEach(input::add);
            }
        }
        return input;
    }

    private static JsonArray assistantItems(AgentMessage.Assistant assistant, LlmRequestConfig cfg) {
        Map<String, AgentMessage.ToolCall> calls = new LinkedHashMap<>();
        assistant.toolCalls().forEach(call -> calls.put(call.id(), call));
        Set<String> emitted = new HashSet<>();
        JsonArray items = new JsonArray();
        JsonObject replay = TransportSupport.replay(assistant, cfg);
        if (replay != null) {
            JsonArray saved = Json.array(replay, "items");
            // Item ids pair a reply with its reasoning; without replayable reasoning they must be left out.
            boolean withIds = true;
            for (JsonElement element : saved) {
                JsonObject item = element.getAsJsonObject();
                if ("reasoning".equals(Json.string(item, "type")) && Json.string(item, "encrypted_content").isBlank()) {
                    withIds = false;
                }
            }
            for (JsonElement element : saved) {
                JsonObject item = element.getAsJsonObject();
                switch (Json.string(item, "type")) {
                    case "reasoning" -> {
                        if (withIds) {
                            items.add(item.deepCopy());
                        }
                    }
                    case "message" -> {
                        String text = Json.string(item, "text");
                        if (text.isBlank()) {
                            continue;
                        }
                        JsonObject out = new JsonObject();
                        out.addProperty("type", "message");
                        out.addProperty("role", "assistant");
                        if (withIds && !Json.string(item, "id").isBlank()) {
                            out.addProperty("id", Json.string(item, "id"));
                        }
                        out.addProperty("status", "completed");
                        JsonArray content = new JsonArray();
                        JsonObject part = new JsonObject();
                        part.addProperty("type", "output_text");
                        part.addProperty("text", text);
                        part.add("annotations", new JsonArray());
                        content.add(part);
                        out.add("content", content);
                        if (!Json.string(item, "phase").isBlank()) {
                            out.addProperty("phase", Json.string(item, "phase"));
                        }
                        items.add(out);
                    }
                    case "function_call" -> {
                        AgentMessage.ToolCall call = calls.get(Json.string(item, "call_id"));
                        if (call != null && emitted.add(call.id())) {
                            String id = Json.string(item, "id");
                            items.add(functionCall(call, withIds && id.startsWith("fc") ? id : null));
                        }
                    }
                    default -> {
                    }
                }
            }
        } else if (!assistant.text().isBlank()) {
            JsonObject item = new JsonObject();
            item.addProperty("role", "assistant");
            item.addProperty("content", assistant.text());
            items.add(item);
        }
        for (AgentMessage.ToolCall call : assistant.toolCalls()) {
            if (emitted.add(call.id())) {
                items.add(functionCall(call, null));
            }
        }
        return items;
    }

    private static JsonObject functionCall(AgentMessage.ToolCall call, String itemId) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call");
        if (itemId != null) {
            item.addProperty("id", itemId);
        }
        item.addProperty("call_id", TransportSupport.safeToolId(call.id()));
        item.addProperty("name", call.name());
        item.addProperty("arguments", TransportSupport.argumentsText(call));
        return item;
    }

    // ------------------------------------------------------------------ responses

    @Override
    protected AgentMessage.Assistant parseStream(InputStream body, Consumer<AssistantMessageEvent> sink,
                                                 AbortSignal signal, LlmRequestConfig cfg) throws IOException {
        return parseStream(new InputStreamReader(body, StandardCharsets.UTF_8), sink, signal, cfg);
    }

    static AgentMessage.Assistant parseStream(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg)
            throws IOException {
        return parseStream(new StringReader(body), sink, AbortSignal.none(), cfg);
    }

    static AgentMessage.Assistant parseStream(Reader reader, Consumer<AssistantMessageEvent> sink, AbortSignal signal,
                                              LlmRequestConfig cfg) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Map<Integer, JsonObject> doneItems = new TreeMap<>();
        Map<Integer, Integer> toolIndexes = new LinkedHashMap<>();
        JsonObject[] terminal = new JsonObject[1];
        String[] error = {""};
        SseDecoder.decode(reader, (event, data) -> {
            JsonObject json = Json.parseObject(data);
            if (json == null) {
                return true;
            }
            int outputIndex = (int) Json.number(json, "output_index");
            switch (Json.string(json, "type")) {
                case "response.output_text.delta", "response.refusal.delta" -> {
                    String delta = Json.string(json, "delta");
                    text.append(delta);
                    if (!delta.isEmpty()) {
                        sink.accept(new AssistantMessageEvent.TextDelta(delta));
                    }
                }
                case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    String delta = Json.string(json, "delta");
                    thinking.append(delta);
                    if (!delta.isEmpty()) {
                        sink.accept(new AssistantMessageEvent.ThinkingDelta(delta));
                    }
                }
                case "response.output_item.added" -> {
                    JsonObject item = Json.object(json, "item");
                    if (item != null && "function_call".equals(Json.string(item, "type"))) {
                        int position = toolIndexes.size();
                        toolIndexes.put(outputIndex, position);
                        sink.accept(new AssistantMessageEvent.ToolCallStart(position, Json.string(item, "call_id"),
                                Json.string(item, "name")));
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    Integer position = toolIndexes.get(outputIndex);
                    String delta = Json.string(json, "delta");
                    if (position != null && !delta.isEmpty()) {
                        sink.accept(new AssistantMessageEvent.ToolCallDelta(position, delta));
                    }
                }
                case "response.output_item.done" -> {
                    JsonObject item = Json.object(json, "item");
                    if (item != null) {
                        doneItems.put(outputIndex, item);
                    }
                }
                case "response.completed", "response.incomplete" -> {
                    terminal[0] = Json.object(json, "response");
                    if (terminal[0] == null) {
                        terminal[0] = new JsonObject();
                    }
                    return false;
                }
                case "response.failed" -> {
                    JsonObject err = Json.object(Json.object(json, "response"), "error");
                    error[0] = "Provider error: " + (err == null ? "response failed"
                            : Json.string(err, "code") + ": " + Json.string(err, "message"));
                    return false;
                }
                case "error" -> {
                    JsonObject err = Json.object(json, "error");
                    JsonObject source = err == null ? json : err;
                    error[0] = "Provider error: " + Json.string(source, "code") + ": " + Json.string(source, "message");
                    return false;
                }
                default -> {
                }
            }
            return true;
        });
        long now = System.currentTimeMillis();
        if (signal.isAborted()) {
            return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.ABORTED,
                    "Operation aborted", now);
        }
        if (!error[0].isBlank()) {
            return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.ERROR, error[0], now);
        }
        if (terminal[0] == null) {
            if (!toolIndexes.isEmpty() || text.isEmpty()) {
                return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.ERROR,
                        "The stream ended before the response completed"
                                + (toolIndexes.isEmpty() ? "" : "; its tool calls were not executed"), now);
            }
            P2SMod.LOGGER.warn("Responses stream ended without a terminal event; keeping text-only response");
            return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.STOP, "", now,
                    TransportSupport.origin(cfg, new JsonObject()), null);
        }
        JsonObject response = terminal[0];
        if (Json.array(response, "output").isEmpty() && !doneItems.isEmpty()) {
            JsonArray output = new JsonArray();
            doneItems.values().forEach(output::add);
            response = response.deepCopy();
            response.add("output", output);
        }
        return fromResponse(response, event -> { }, cfg);
    }

    @Override
    protected AgentMessage.Assistant parseResponse(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg) {
        JsonObject root = Json.parseObject(body);
        if (root == null) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response is not JSON: " + truncate(body, 200));
        }
        if (root.has("error") && root.get("error").isJsonObject()) {
            JsonObject err = root.getAsJsonObject("error");
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Provider error: " + Json.string(err, "message"));
        }
        return fromResponse(root, sink, cfg);
    }

    /** Neutral reply from a response object; {@code sink} receives the text once (non-streamed path). */
    static AgentMessage.Assistant fromResponse(JsonObject response, Consumer<AssistantMessageEvent> sink,
                                               LlmRequestConfig cfg) {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<AgentMessage.ToolCall> calls = new ArrayList<>();
        JsonArray replayItems = new JsonArray();
        String status = Json.string(response, "status");
        String incomplete = Json.string(Json.object(response, "incomplete_details"), "reason");
        boolean truncated = "incomplete".equals(status) && "max_output_tokens".equals(incomplete);
        for (JsonElement element : Json.array(response, "output")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            switch (Json.string(item, "type")) {
                case "reasoning" -> {
                    StringBuilder summary = new StringBuilder();
                    for (String key : new String[]{"summary", "content"}) {
                        for (JsonElement part : Json.array(item, key)) {
                            if (part.isJsonObject()) {
                                if (!summary.isEmpty()) {
                                    summary.append("\n\n");
                                }
                                summary.append(Json.string(part.getAsJsonObject(), "text"));
                            }
                        }
                        if (!summary.isEmpty()) {
                            break;
                        }
                    }
                    if (!thinking.isEmpty() && !summary.isEmpty()) {
                        thinking.append("\n\n");
                    }
                    thinking.append(summary);
                    replayItems.add(item.deepCopy());
                }
                case "message" -> {
                    StringBuilder messageText = new StringBuilder();
                    for (JsonElement part : Json.array(item, "content")) {
                        if (part.isJsonObject()) {
                            JsonObject content = part.getAsJsonObject();
                            messageText.append("refusal".equals(Json.string(content, "type"))
                                    ? Json.string(content, "refusal") : Json.string(content, "text"));
                        }
                    }
                    text.append(messageText);
                    JsonObject saved = new JsonObject();
                    saved.addProperty("type", "message");
                    saved.addProperty("id", Json.string(item, "id"));
                    saved.addProperty("text", messageText.toString());
                    if (!Json.string(item, "phase").isBlank()) {
                        saved.addProperty("phase", Json.string(item, "phase"));
                    }
                    replayItems.add(saved);
                }
                case "function_call" -> {
                    AgentMessage.ToolCall call = ToolCallAssembler.decodeText(Json.string(item, "call_id"),
                            Json.string(item, "name"), Json.string(item, "arguments"));
                    if (truncated) {
                        call = AgentMessage.ToolCall.invalid(call.id(), call.name(), call.rawArguments(),
                                "the response was cut off by the output length limit");
                    }
                    calls.add(call);
                    JsonObject saved = new JsonObject();
                    saved.addProperty("type", "function_call");
                    saved.addProperty("id", Json.string(item, "id"));
                    saved.addProperty("call_id", Json.string(item, "call_id"));
                    replayItems.add(saved);
                }
                default -> {
                }
            }
        }
        if (!thinking.isEmpty()) {
            sink.accept(new AssistantMessageEvent.ThinkingDelta(thinking.toString()));
        }
        if (!text.isEmpty()) {
            sink.accept(new AssistantMessageEvent.TextDelta(text.toString()));
        }
        long now = System.currentTimeMillis();
        if ("failed".equals(status) || "cancelled".equals(status)) {
            JsonObject err = Json.object(response, "error");
            return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.ERROR,
                    "Provider error: " + (err == null ? status : Json.string(err, "message")), now);
        }
        if ("incomplete".equals(status) && !truncated) {
            return new AgentMessage.Assistant(text.toString(), thinking.toString(), List.of(), StopReason.ERROR,
                    "Response incomplete" + (incomplete.isBlank() ? "" : ": " + incomplete), now);
        }
        if (calls.isEmpty() && text.toString().isBlank() && thinking.toString().isBlank()) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Model returned an empty response"
                    + (status.isBlank() ? "" : " (status=" + status + ")"));
        }
        StopReason reason = truncated ? StopReason.LENGTH : calls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
        JsonObject data = new JsonObject();
        data.add("items", replayItems);
        JsonObject usage = Json.object(response, "usage");
        long cached = Json.number(Json.object(usage, "input_tokens_details"), "cached_tokens");
        AgentMessage.Usage parsed = usage == null ? AgentMessage.Usage.NONE : new AgentMessage.Usage(
                Math.max(0, Json.number(usage, "input_tokens") - cached), Json.number(usage, "output_tokens"), cached, 0,
                Json.number(Json.object(usage, "output_tokens_details"), "reasoning_tokens"));
        return new AgentMessage.Assistant(text.toString(), thinking.toString(), calls, reason, "", now,
                TransportSupport.origin(cfg, data), parsed);
    }
}
