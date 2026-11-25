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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anthropic Messages ({@code POST /v1/messages}). Thinking blocks are kept with their
 * signatures (and redacted_thinking with its data) as replay data and sent back verbatim to
 * the same model, which Anthropic requires inside a tool-use loop while thinking is on.
 */
public final class AnthropicMessages extends HttpTransport {
    static final String DEFAULT_URL = "https://api.anthropic.com/v1/messages";
    static final String DEFAULT_VERSION = "2023-06-01";
    static final int DEFAULT_MAX_TOKENS = 8192;
    private static final Pattern MODERN_ID = Pattern.compile("claude-(opus|sonnet|haiku|fable)-(\\d+)(?:-(\\d{1,2}))?(?!\\d)");

    public AnthropicMessages(Supplier<LlmRequestConfig> config) {
        super(config);
    }

    @Override
    protected HttpCall prepare(Context context, LlmRequestConfig cfg) {
        Map<String, String> headers = new LinkedHashMap<>();
        String version = cfg.compatString("anthropicVersion");
        headers.put("anthropic-version", version == null ? DEFAULT_VERSION : version);
        if ("bearer".equalsIgnoreCase(cfg.compatString("authHeader"))) {
            headers.put("Authorization", "Bearer " + cfg.apiKey());
        } else {
            headers.put("x-api-key", cfg.apiKey());
        }
        if (cfg.streaming()) {
            headers.put("Accept", "text/event-stream");
        }
        return new HttpCall(endpoint(cfg.apiUrl()), headers, buildRequestBody(context, cfg), cfg.streaming());
    }

    static String endpoint(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_URL;
        }
        return TransportSupport.endpoint(url, "messages", true);
    }

    // ------------------------------------------------------------------ model hints

    /** Claude 4.6+ / 5 / Fable use adaptive thinking with an effort; older models a token budget. */
    static boolean adaptiveThinking(LlmRequestConfig cfg) {
        String mode = cfg.compatString("thinkingMode");
        if (mode != null) {
            return "adaptive".equalsIgnoreCase(mode);
        }
        int[] version = version(cfg.model());
        if (version == null) {
            return false;
        }
        return version[0] < 0 || version[0] >= 5 || (version[0] == 4 && version[1] >= 6);
    }

    /** Opus 4.7+, Sonnet 5+, Haiku 5+ and Fable reject temperature (pi model data). */
    static boolean supportsTemperature(LlmRequestConfig cfg) {
        Boolean override = cfg.compatBool("supportsTemperature");
        if (override != null) {
            return override;
        }
        int[] version = version(cfg.model());
        if (version == null) {
            return true;
        }
        if (version[0] < 0 || version[0] >= 5) {
            return false;
        }
        return !(version[2] == 'o' && version[0] == 4 && version[1] >= 7);
    }

    /** {major, minor, family initial}; major -1 for Fable; null when the id is not a recognised Claude id. */
    private static int[] version(String model) {
        Matcher matcher = MODERN_ID.matcher(model == null ? "" : model.toLowerCase(java.util.Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        String family = matcher.group(1);
        int major = "fable".equals(family) ? -1 : Integer.parseInt(matcher.group(2));
        int minor = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new int[]{major, minor, family.charAt(0)};
    }

    static String effort(ThinkingLevel level, LlmRequestConfig cfg) {
        int[] v = version(cfg.model());
        boolean nativeXhigh = v != null && (v[0] < 0 || (v[2] == 's' && v[0] >= 5)
                || (v[2] == 'o' && (v[0] > 4 || (v[0] == 4 && v[1] >= 7))));
        Boolean override = cfg.compatBool("supportsXhigh");

        return switch (level) {
            case OFF, MINIMAL, LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case XHIGH -> (override == null ? nativeXhigh : override) ? "xhigh" : "high";
            case MAX -> "max";
        };
    }

    // ------------------------------------------------------------------ request

    public static JsonObject buildRequestBody(Context context, LlmRequestConfig cfg) {
        boolean cache = Boolean.TRUE.equals(cfg.compatBool("cacheControl"));
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        ThinkingLevel level = cfg.thinking();
        boolean thinking = level != null && level.enabled();
        boolean adaptive = adaptiveThinking(cfg);
        int maxTokens = cfg.maxTokens() == null ? DEFAULT_MAX_TOKENS : cfg.maxTokens();
        int budget = 0;
        if (thinking && !adaptive) {
            budget = cfg.thinkingBudget() != null ? cfg.thinkingBudget() : level.defaultBudget();
            if (budget < 1024) throw new IllegalArgumentException("Claude thinkingBudget must be at least 1024");
            if (cfg.maxTokens() == null) {
                maxTokens = Math.addExact(DEFAULT_MAX_TOKENS, budget);
            } else {
                if (maxTokens <= 1024) throw new IllegalArgumentException("Claude thinking requires maxTokens > 1024");
                budget = Math.min(budget, maxTokens - 1);
            }
        }
        body.addProperty("max_tokens", maxTokens);
        if (!context.systemPrompt().isBlank()) {
            if (cache) {
                JsonArray system = new JsonArray();
                JsonObject block = textBlock(context.systemPrompt());
                block.add("cache_control", ephemeral());
                system.add(block);
                body.add("system", system);
            } else {
                body.addProperty("system", context.systemPrompt());
            }
        }
        JsonArray messages = convertMessages(context.messages(), cfg);
        if (cache && !messages.isEmpty()) {
            JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
            if ("user".equals(Json.string(last, "role"))) {
                JsonArray content = last.getAsJsonArray("content");
                content.get(content.size() - 1).getAsJsonObject().add("cache_control", ephemeral());
            }
        }
        body.add("messages", messages);
        if (cfg.useToolCall() && !context.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (TransportSupport.ToolSpec spec : TransportSupport.toolSpecs(context.tools())) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", spec.name());
                if (!spec.description().isBlank()) {
                    tool.addProperty("description", spec.description());
                }
                tool.add("input_schema", spec.parameters());
                tools.add(tool);
            }
            if (cache) {
                tools.get(tools.size() - 1).getAsJsonObject().add("cache_control", ephemeral());
            }
            body.add("tools", tools);
        }
        if (level != null) {
            JsonObject config = new JsonObject();
            if (!thinking) {
                config.addProperty("type", "disabled");
            } else if (adaptive) {
                config.addProperty("type", "adaptive");
                JsonObject output = new JsonObject();
                output.addProperty("effort", effort(level, cfg));
                body.add("output_config", output);
            } else {
                config.addProperty("type", "enabled");
                config.addProperty("budget_tokens", budget);
            }
            String display = cfg.compatString("thinkingDisplay");
            if (thinking && display != null) {
                config.addProperty("display", display);
            }
            body.add("thinking", config);
        }
        // Thinking only allows temperature 1, and newer models reject sampling parameters entirely.
        boolean sampling = !thinking && supportsTemperature(cfg);
        if (cfg.temperature() != null && sampling) {
            body.addProperty("temperature", cfg.temperature());
        }
        if (cfg.topP() != null && sampling) {
            body.addProperty("top_p", cfg.topP());
        }
        if (cfg.streaming()) {
            body.addProperty("stream", true);
        }
        return body;
    }

    static JsonArray convertMessages(List<AgentMessage> messages, LlmRequestConfig cfg) {
        JsonArray out = new JsonArray();
        for (AgentMessage message : messages) {
            if (message instanceof AgentMessage.User user) {
                if (!user.content().isBlank()) {
                    append(out, "user", textBlock(user.content()));
                }
            } else if (message instanceof AgentMessage.ToolResult result) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_result");
                block.addProperty("tool_use_id", TransportSupport.safeToolId(result.toolCallId()));
                block.addProperty("content", GSON.toJson(result.content()));
                if (result.isError()) {
                    block.addProperty("is_error", true);
                }
                append(out, "user", block);
            } else if (message instanceof AgentMessage.Assistant assistant) {
                JsonArray blocks = assistantBlocks(assistant, cfg);
                for (JsonElement block : blocks) {
                    append(out, "assistant", block.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static JsonArray assistantBlocks(AgentMessage.Assistant assistant, LlmRequestConfig cfg) {
        Map<String, AgentMessage.ToolCall> calls = new LinkedHashMap<>();
        assistant.toolCalls().forEach(call -> calls.put(call.id(), call));
        JsonArray blocks = new JsonArray();
        Set<String> emitted = new HashSet<>();
        JsonObject replay = TransportSupport.replay(assistant, cfg);
        if (replay != null) {
            for (JsonElement element : Json.array(replay, "blocks")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                switch (Json.string(block, "type")) {
                    case "thinking" -> {
                        // An unsigned thinking block cannot be verified and is rejected; drop it.
                        if (!Json.string(block, "signature").isBlank()) {
                            blocks.add(block.deepCopy());
                        }
                    }
                    case "redacted_thinking" -> blocks.add(block.deepCopy());
                    case "text" -> {
                        if (!Json.string(block, "text").isBlank()) {
                            blocks.add(textBlock(Json.string(block, "text")));
                        }
                    }
                    case "tool_use" -> {
                        AgentMessage.ToolCall call = calls.get(Json.string(block, "id"));
                        if (call != null && emitted.add(call.id())) {
                            blocks.add(toolUse(call));
                        }
                    }
                    default -> {
                        // Unknown block kinds are not echoed.
                    }
                }
            }
        } else if (!assistant.text().isBlank()) {
            blocks.add(textBlock(assistant.text()));
        }
        for (AgentMessage.ToolCall call : assistant.toolCalls()) {
            if (emitted.add(call.id())) {
                blocks.add(toolUse(call));
            }
        }
        return blocks;
    }

    private static JsonObject toolUse(AgentMessage.ToolCall call) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", TransportSupport.safeToolId(call.id()));
        block.addProperty("name", call.name());
        block.add("input", TransportSupport.arguments(call));
        return block;
    }

    /** Consecutive blocks of the same role share one message; tool results must come first in it. */
    private static void append(JsonArray out, String role, JsonObject block) {
        JsonObject last = out.isEmpty() ? null : out.get(out.size() - 1).getAsJsonObject();
        if (last != null && role.equals(Json.string(last, "role"))) {
            last.getAsJsonArray("content").add(block);
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        JsonArray content = new JsonArray();
        content.add(block);
        message.add("content", content);
        out.add(message);
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static JsonObject ephemeral() {
        JsonObject control = new JsonObject();
        control.addProperty("type", "ephemeral");
        return control;
    }

    // ------------------------------------------------------------------ responses

    @Override
    protected AgentMessage.Assistant parseStream(InputStream body, Consumer<AssistantMessageEvent> sink,
                                                 AbortSignal signal, LlmRequestConfig cfg) throws IOException {
        return parseStream(new InputStreamReader(body, StandardCharsets.UTF_8), sink, signal, cfg);
    }

    static AgentMessage.Assistant parseStream(Reader reader, Consumer<AssistantMessageEvent> sink, AbortSignal signal,
                                              LlmRequestConfig cfg) throws IOException {
        State state = new State(sink);
        SseDecoder.decode(reader, (event, data) -> {
            JsonObject json = Json.parseObject(data);
            if (json == null) {
                return true;
            }
            return state.accept(json);
        });
        if (signal.isAborted()) {
            return new AgentMessage.Assistant(state.text(), state.thinking(), List.of(), StopReason.ABORTED,
                    "Operation aborted", System.currentTimeMillis());
        }
        return state.finish(cfg);
    }

    static AgentMessage.Assistant parseStream(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg)
            throws IOException {
        return parseStream(new StringReader(body), sink, AbortSignal.none(), cfg);
    }

    @Override
    protected AgentMessage.Assistant parseResponse(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg) {
        return parseResponseBody(body, sink, cfg);
    }

    static AgentMessage.Assistant parseResponseBody(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg) {
        JsonObject root = Json.parseObject(body);
        if (root == null) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response is not JSON: " + truncate(body, 200));
        }
        State state = new State(sink);
        if ("error".equals(Json.string(root, "type"))) {
            state.accept(root);
            return state.finish(cfg);
        }
        JsonObject start = new JsonObject();
        start.addProperty("type", "message_start");
        start.add("message", root);
        state.accept(start);
        JsonArray content = Json.array(root, "content");
        for (int i = 0; i < content.size(); i++) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "content_block_start");
            event.addProperty("index", i);
            event.add("content_block", content.get(i));
            state.accept(event);
        }
        state.stopReason = Json.string(root, "stop_reason");
        state.completed = true;
        return state.finish(cfg);
    }

    private static final class Block {
        String type = "";
        final StringBuilder text = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
        final StringBuilder json = new StringBuilder();
        String id = "";
        String name = "";
        String data = "";
        JsonObject input;
    }

    private static final class State {
        final Consumer<AssistantMessageEvent> sink;
        final Map<Integer, Block> blocks = new TreeMap<>();
        String stopReason = "";
        String error = "";
        boolean completed;
        long input;
        long output;
        long cacheRead;
        long cacheWrite;
        int toolIndex;
        final Map<Integer, Integer> toolIndexes = new LinkedHashMap<>();

        State(Consumer<AssistantMessageEvent> sink) {
            this.sink = sink;
        }

        /** Returns false to stop reading. */
        boolean accept(JsonObject event) {
            switch (Json.string(event, "type")) {
                case "message_start" -> readUsage(Json.object(Json.object(event, "message"), "usage"));
                case "content_block_start" -> {
                    int index = (int) Json.number(event, "index");
                    JsonObject start = Json.object(event, "content_block");
                    if (start == null) {
                        return true;
                    }
                    Block block = new Block();
                    block.type = Json.string(start, "type");
                    blocks.put(index, block);
                    switch (block.type) {
                        case "text" -> appendText(block, Json.string(start, "text"));
                        case "thinking" -> {
                            appendThinking(block, Json.string(start, "thinking"));
                            block.signature.append(Json.string(start, "signature"));
                        }
                        case "redacted_thinking" -> block.data = Json.string(start, "data");
                        case "tool_use" -> {
                            block.id = Json.string(start, "id");
                            block.name = Json.string(start, "name");
                            JsonObject input = Json.object(start, "input");
                            if (input != null && !input.isEmpty()) {
                                block.input = input;
                            }
                            int position = toolIndex++;
                            toolIndexes.put(index, position);
                            sink.accept(new AssistantMessageEvent.ToolCallStart(position, block.id, block.name));
                        }
                        default -> {
                            // server_tool_use and other kinds are ignored.
                        }
                    }
                }
                case "content_block_delta" -> {
                    int index = (int) Json.number(event, "index");
                    Block block = blocks.get(index);
                    JsonObject delta = Json.object(event, "delta");
                    if (block == null || delta == null) {
                        return true;
                    }
                    switch (Json.string(delta, "type")) {
                        case "text_delta" -> appendText(block, Json.string(delta, "text"));
                        case "thinking_delta" -> appendThinking(block, Json.string(delta, "thinking"));
                        case "signature_delta" -> block.signature.append(Json.string(delta, "signature"));
                        case "input_json_delta" -> {
                            String part = Json.string(delta, "partial_json");
                            block.json.append(part);
                            if (!part.isEmpty() && toolIndexes.containsKey(index)) {
                                sink.accept(new AssistantMessageEvent.ToolCallDelta(toolIndexes.get(index), part));
                            }
                        }
                        default -> {
                        }
                    }
                }
                case "message_delta" -> {
                    String reason = Json.string(Json.object(event, "delta"), "stop_reason");
                    if (!reason.isBlank()) {
                        stopReason = reason;
                    }
                    readUsage(Json.object(event, "usage"));
                }
                case "message_stop" -> {
                    completed = true;
                    return false;
                }
                case "error" -> {
                    JsonObject err = Json.object(event, "error");
                    error = "Provider error: " + (err == null ? event.toString()
                            : Json.string(err, "type") + ": " + Json.string(err, "message"));
                    return false;
                }
                default -> {
                    // ping and unknown events.
                }
            }
            return true;
        }

        private void readUsage(JsonObject usage) {
            if (usage == null) {
                return;
            }
            if (usage.has("input_tokens") && !usage.get("input_tokens").isJsonNull()) {
                input = Json.number(usage, "input_tokens");
            }
            if (usage.has("output_tokens") && !usage.get("output_tokens").isJsonNull()) {
                output = Json.number(usage, "output_tokens");
            }
            if (usage.has("cache_read_input_tokens") && !usage.get("cache_read_input_tokens").isJsonNull()) {
                cacheRead = Json.number(usage, "cache_read_input_tokens");
            }
            if (usage.has("cache_creation_input_tokens") && !usage.get("cache_creation_input_tokens").isJsonNull()) {
                cacheWrite = Json.number(usage, "cache_creation_input_tokens");
            }
        }

        private void appendText(Block block, String token) {
            if (!token.isEmpty()) {
                block.text.append(token);
                sink.accept(new AssistantMessageEvent.TextDelta(token));
            }
        }

        private void appendThinking(Block block, String token) {
            if (!token.isEmpty()) {
                block.text.append(token);
                sink.accept(new AssistantMessageEvent.ThinkingDelta(token));
            }
        }

        String text() {
            StringBuilder out = new StringBuilder();
            blocks.values().stream().filter(b -> "text".equals(b.type)).forEach(b -> out.append(b.text));
            return out.toString();
        }

        String thinking() {
            StringBuilder out = new StringBuilder();
            blocks.values().stream().filter(b -> "thinking".equals(b.type)).forEach(b -> {
                if (!out.isEmpty() && !b.text.isEmpty()) {
                    out.append("\n\n");
                }
                out.append(b.text);
            });
            return out.toString();
        }

        AgentMessage.Assistant finish(LlmRequestConfig cfg) {
            long now = System.currentTimeMillis();
            String text = text();
            String thinking = thinking();
            if (!error.isBlank()) {
                return new AgentMessage.Assistant(text, thinking, List.of(), StopReason.ERROR, error, now);
            }
            boolean hasTools = blocks.values().stream().anyMatch(b -> "tool_use".equals(b.type));
            boolean done = completed || !stopReason.isBlank();
            if (!done && hasTools) {
                return new AgentMessage.Assistant(text, thinking, List.of(), StopReason.ERROR,
                        "The stream ended before the response completed; its tool calls were not executed", now);
            }
            if (!done && text.isEmpty()) {
                return new AgentMessage.Assistant("", thinking, List.of(), StopReason.ERROR,
                        "The stream ended without a response", now);
            }
            if (!done) {
                P2SMod.LOGGER.warn("Anthropic stream ended without stop_reason; keeping text-only response");
            }
            if ("refusal".equals(stopReason)) {
                return new AgentMessage.Assistant(text, thinking, List.of(), StopReason.ERROR,
                        "The model refused to complete the request", now);
            }
            boolean truncated = "max_tokens".equals(stopReason) || "model_context_window_exceeded".equals(stopReason);
            List<AgentMessage.ToolCall> calls = new ArrayList<>();
            JsonArray replay = new JsonArray();
            for (Block block : blocks.values()) {
                JsonObject saved = new JsonObject();
                saved.addProperty("type", block.type);
                switch (block.type) {
                    case "text" -> saved.addProperty("text", block.text.toString());
                    case "thinking" -> {
                        saved.addProperty("thinking", block.text.toString());
                        saved.addProperty("signature", block.signature.toString());
                    }
                    case "redacted_thinking" -> saved.addProperty("data", block.data);
                    case "tool_use" -> {
                        AgentMessage.ToolCall call = block.json.isEmpty() && block.input != null
                                ? AgentMessage.ToolCall.valid(block.id, block.name, block.input.toString(), block.input)
                                : ToolCallAssembler.decodeText(block.id, block.name, block.json.toString());
                        if (truncated) {
                            call = AgentMessage.ToolCall.invalid(call.id(), call.name(), call.rawArguments(),
                                    "the response was cut off by the output length limit");
                        }
                        calls.add(call);
                        saved.addProperty("id", block.id);
                        saved.addProperty("name", block.name);
                    }
                    default -> {
                        continue;
                    }
                }
                replay.add(saved);
            }
            if (calls.isEmpty() && text.isBlank() && thinking.isBlank() && replay.isEmpty()) {
                return AgentMessage.Assistant.failure(StopReason.ERROR, "Model returned an empty response"
                        + (stopReason.isBlank() ? "" : " (stop_reason=" + stopReason + ")"));
            }
            StopReason reason = truncated ? StopReason.LENGTH : calls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
            JsonObject data = new JsonObject();
            data.add("blocks", replay);
            return new AgentMessage.Assistant(text, thinking, calls, reason, "", now, TransportSupport.origin(cfg, data),
                    new AgentMessage.Usage(input, output, cacheRead, cacheWrite, 0));
        }
    }
}
