package com.p2s.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.P2SMod;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentMessage.StopReason;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.AssistantMessageEvent;
import com.p2s.agent.core.StreamFunction;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * OpenAI-compatible Chat Completions transport implementing the {@link StreamFunction}
 * contract: it never throws, it maps abort to {@code Call.cancel()}, and it refuses to
 * return tool calls from a response that did not complete.
 */
public final class OpenAiChatCompletions implements StreamFunction {
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final double TEMPERATURE = 0.4;
    private static final Map<Integer, OkHttpClient> CLIENTS = new ConcurrentHashMap<>();

    private final Supplier<LlmRequestConfig> config;

    public OpenAiChatCompletions(Supplier<LlmRequestConfig> config) {
        this.config = config;
    }

    @Override
    public AgentMessage.Assistant stream(Context context, AbortSignal signal, Consumer<AssistantMessageEvent> sink) {
        LlmRequestConfig cfg;
        try {
            cfg = config.get();
        } catch (RuntimeException ex) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Invalid model configuration: " + ex.getMessage());
        }
        JsonObject body = buildRequestBody(context, cfg.model(), cfg.useToolCall(), cfg.streaming());
        P2SMod.LOGGER.info("LLM request -> url={}, model={}, timeout={}s, stream={}, messages={}, tools={}",
                cfg.apiUrl(), cfg.model(), cfg.timeoutSeconds(), cfg.streaming(),
                body.getAsJsonArray("messages").size(), body.has("tools") ? body.getAsJsonArray("tools").size() : 0);
        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] LLM request body: {}", body);
        }
        Request request;
        try {
            request = new Request.Builder()
                    .url(cfg.apiUrl())
                    .post(RequestBody.create(GSON.toJson(body), JSON))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .build();
        } catch (RuntimeException ex) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Invalid API URL: " + ex.getMessage());
        }
        Call call = client(cfg.timeoutSeconds()).newCall(request);
        try (AbortSignal.Registration ignored = signal.onAbort(call::cancel)) {
            if (signal.isAborted()) {
                return AgentMessage.Assistant.failure(StopReason.ABORTED, "Operation aborted");
            }
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful()) {
                    String error = responseBody == null ? "" : responseBody.string();
                    P2SMod.LOGGER.error("LLM request failed status={}, body={}", response.code(), truncate(error, 800));
                    return AgentMessage.Assistant.failure(StopReason.ERROR,
                            "HTTP " + response.code() + (error.isBlank() ? "" : ": " + truncate(error, 300)));
                }
                if (responseBody == null) {
                    return AgentMessage.Assistant.failure(StopReason.ERROR, "Empty response body");
                }
                String contentType = response.header("Content-Type", "");
                AgentMessage.Assistant result;
                if (cfg.streaming() && contentType != null
                        && (contentType.contains("text/event-stream") || contentType.contains("text/plain"))) {
                    try (Reader reader = new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8)) {
                        result = parseStream(reader, sink, signal);
                    }
                } else {
                    String text = responseBody.string();
                    if (P2SMod.DEBUG) {
                        P2SMod.LOGGER.info("[DEBUG] LLM response body: {}", text);
                    }
                    result = parseResponse(text, sink);
                }
                P2SMod.LOGGER.info("LLM response -> stop={}, toolCalls={}, textLen={}, thinkingLen={}{}",
                        result.stopReason(), result.toolCalls().size(), result.text().length(),
                        result.thinking().length(),
                        result.errorMessage().isBlank() ? "" : ", error=" + result.errorMessage());
                return result;
            }
        } catch (IOException | RuntimeException ex) {
            if (signal.isAborted() || call.isCanceled()) {
                return AgentMessage.Assistant.failure(StopReason.ABORTED, "Operation aborted");
            }
            if (ex instanceof InterruptedIOException) {
                return AgentMessage.Assistant.failure(StopReason.ERROR,
                        "Timed out after " + cfg.timeoutSeconds() + "s");
            }
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            P2SMod.LOGGER.warn("LLM request error: {}", message);
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Request failed: " + message);
        }
    }

    /** Request body for the given context; tools are advertised in context order. */
    public static JsonObject buildRequestBody(Context context, String model, boolean useToolCall, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model == null ? "" : model);
        JsonArray messages = new JsonArray();
        for (JsonObject message : OpenAiMessageCodec.toWire(context.systemPrompt(), context.messages())) {
            messages.add(message);
        }
        body.add("messages", messages);
        body.addProperty("temperature", TEMPERATURE);
        if (stream) {
            body.addProperty("stream", true);
        }
        if (useToolCall && !context.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (AgentTool tool : context.tools()) {
                tools.add(tool.declaration());
            }
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }
        return body;
    }

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
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] SSE chunk: {}", payload);
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
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException ex) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response is not JSON: " + truncate(text, 200));
        }
        if (root.has("error") && !root.get("error").isJsonNull()) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, describeError(root.get("error")));
        }
        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray() ? root.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response has no choices");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : null;
        if (message == null) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Response has no message");
        }
        String content = OpenAiMessageCodec.contentText(message.get("content"));
        String thinking = reasoning(message);
        if (!thinking.isEmpty()) {
            sink.accept(new AssistantMessageEvent.ThinkingDelta(thinking));
        }
        if (!content.isEmpty()) {
            sink.accept(new AssistantMessageEvent.TextDelta(content));
        }
        String finish = OpenAiMessageCodec.string(choice, "finish_reason");
        boolean truncated = "length".equals(finish);
        List<AgentMessage.ToolCall> calls = new java.util.ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject call = element.getAsJsonObject();
                JsonObject function = call.has("function") && call.get("function").isJsonObject()
                        ? call.getAsJsonObject("function") : new JsonObject();
                AgentMessage.ToolCall decoded = ToolCallAssembler.decode(OpenAiMessageCodec.string(call, "id"),
                        OpenAiMessageCodec.string(function, "name"), function.get("arguments"));
                calls.add(truncated
                        ? AgentMessage.ToolCall.invalid(decoded.id(), decoded.name(), decoded.rawArguments(),
                                "the response was cut off by the output length limit")
                        : decoded);
            }
        } else if (message.has("function_call") && message.get("function_call").isJsonObject()) {
            JsonObject function = message.getAsJsonObject("function_call");
            calls.add(ToolCallAssembler.decode("", OpenAiMessageCodec.string(function, "name"), function.get("arguments")));
        }
        StopReason reason = truncated ? StopReason.LENGTH : calls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
        if (calls.isEmpty() && content.isBlank() && thinking.isBlank()) {
            return AgentMessage.Assistant.failure(StopReason.ERROR,
                    "Model returned an empty response" + (finish.isBlank() ? "" : " (finish_reason=" + finish + ")"));
        }
        return new AgentMessage.Assistant(content, thinking, calls, reason, "", System.currentTimeMillis());
    }

    /**
     * One-shot text completion over raw wire messages (used by history compaction). Throws on
     * failure because callers fall back to keeping the uncompacted history.
     */
    public static String completeText(List<JsonObject> wireMessages, LlmRequestConfig cfg, AbortSignal signal)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        JsonArray messages = new JsonArray();
        wireMessages.forEach(messages::add);
        body.add("messages", messages);
        body.addProperty("temperature", TEMPERATURE);
        Request request = new Request.Builder()
                .url(cfg.apiUrl())
                .post(RequestBody.create(GSON.toJson(body), JSON))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .build();
        Call call = client(cfg.timeoutSeconds()).newCall(request);
        try (AbortSignal.Registration ignored = signal.onAbort(call::cancel)) {
            if (signal.isAborted()) {
                throw new IOException("Operation aborted");
            }
            try (Response response = call.execute()) {
                String text = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + ": " + truncate(text, 300));
                }
                AgentMessage.Assistant parsed = parseResponse(text, event -> { });
                if (parsed.failed()) {
                    throw new IOException(parsed.errorMessage());
                }
                return parsed.text();
            }
        }
    }

    /** For tests: parse an SSE body held in memory. */
    static AgentMessage.Assistant parseStream(String body, Consumer<AssistantMessageEvent> sink) throws IOException {
        return parseStream(new StringReader(body), sink, AbortSignal.none());
    }

    private static String reasoning(JsonObject delta) {
        for (String key : new String[]{"reasoning_content", "reasoning"}) {
            if (delta.has(key) && delta.get(key).isJsonPrimitive()) {
                return delta.get(key).getAsString();
            }
        }
        return "";
    }

    private static String describeError(JsonElement error) {
        if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
            return "Provider error: " + OpenAiMessageCodec.string(error.getAsJsonObject(), "message");
        }
        return "Provider error: " + truncate(error.toString(), 300);
    }

    private static OkHttpClient client(int timeoutSeconds) {
        return CLIENTS.computeIfAbsent(timeoutSeconds, seconds -> new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(seconds))
                .readTimeout(Duration.ofSeconds(seconds))
                .writeTimeout(Duration.ofSeconds(seconds))
                .callTimeout(Duration.ofSeconds(seconds))
                .build());
    }

    static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "...(len=" + text.length() + ")";
    }

    private static final class StreamState {
        final Consumer<AssistantMessageEvent> sink;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final ToolCallAssembler tools = new ToolCallAssembler();
        String finishReason = "";
        String providerError = "";
        boolean done;
        int malformedChunks;

        StreamState(Consumer<AssistantMessageEvent> sink) {
            this.sink = sink;
        }

        void accept(JsonObject chunk) {
            if (!chunk.has("choices") || !chunk.get("choices").isJsonArray()) {
                return;
            }
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
                return;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String finish = OpenAiMessageCodec.string(choice, "finish_reason");
            if (!finish.isBlank()) {
                finishReason = finish;
            }
            JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                    ? choice.getAsJsonObject("delta") : null;
            if (delta == null) {
                return;
            }
            String reasoningDelta = reasoning(delta);
            if (!reasoningDelta.isEmpty()) {
                thinking.append(reasoningDelta);
                sink.accept(new AssistantMessageEvent.ThinkingDelta(reasoningDelta));
            }
            if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
                String token = delta.get("content").getAsString();
                if (!token.isEmpty()) {
                    text.append(token);
                    sink.accept(new AssistantMessageEvent.TextDelta(token));
                }
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
                    JsonObject function = call.has("function") && call.get("function").isJsonObject()
                            ? call.getAsJsonObject("function") : new JsonObject();
                    String args = function.has("arguments") && function.get("arguments").isJsonPrimitive()
                            ? function.get("arguments").getAsString()
                            : function.has("arguments") && function.get("arguments").isJsonObject()
                            ? function.get("arguments").toString() : null;
                    boolean created = tools.append(index, OpenAiMessageCodec.string(call, "id"),
                            OpenAiMessageCodec.string(function, "name"), args);
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
            boolean truncated = "length".equals(finishReason);
            List<AgentMessage.ToolCall> calls = tools.finish(truncated);
            if (calls.isEmpty() && text.toString().isBlank() && thinking.toString().isBlank()) {
                return AgentMessage.Assistant.failure(StopReason.ERROR, "Model returned an empty response"
                        + (finishReason.isBlank() ? "" : " (finish_reason=" + finishReason + ")"));
            }
            StopReason reason = truncated ? StopReason.LENGTH : calls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
            return new AgentMessage.Assistant(text.toString(), thinking.toString(), calls, reason, "", now);
        }
    }
}
