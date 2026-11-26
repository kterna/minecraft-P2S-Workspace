package com.p2s.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.P2SMod;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentMessage.StopReason;
import com.p2s.agent.core.AssistantMessageEvent;
import com.p2s.agent.core.StreamFunction;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared HTTP plumbing for every protocol: one POST per turn, abort mapped to
 * {@code Call.cancel()}, HTTP and transport failures encoded as ERROR / ABORTED messages so
 * the {@link StreamFunction} contract (never throw) holds for all transports.
 */
public abstract class HttpTransport implements StreamFunction {
    protected static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json");
    private static final Map<Integer, OkHttpClient> CLIENTS = new ConcurrentHashMap<>();

    private final Supplier<LlmRequestConfig> config;

    protected HttpTransport(Supplier<LlmRequestConfig> config) {
        this.config = config;
    }

    /** A prepared request. Header values may contain secrets and are never logged. */
    public record HttpCall(String url, Map<String, String> headers, JsonObject body, boolean stream) {
        public HttpCall {
            headers = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        }
    }

    /** Builds the request; throw IllegalArgumentException for configuration errors. */
    protected abstract HttpCall prepare(Context context, LlmRequestConfig cfg);

    /** Parses a streamed body. Must check {@code signal} only for the final state; cancel closes the stream. */
    protected abstract AgentMessage.Assistant parseStream(InputStream body, Consumer<AssistantMessageEvent> sink,
                                                          AbortSignal signal, LlmRequestConfig cfg) throws IOException;

    /** Parses a complete JSON body. */
    protected abstract AgentMessage.Assistant parseResponse(String body, Consumer<AssistantMessageEvent> sink,
                                                            LlmRequestConfig cfg);

    /** Hook for request signing (Bedrock SigV4); called with the final URL and body bytes. */
    protected void authorize(Request.Builder request, HttpUrl url, byte[] body, LlmRequestConfig cfg) {
    }

    /** True when the response should be read as a stream. */
    protected boolean isStreamResponse(String contentType) {
        return contentType != null && (contentType.contains("text/event-stream") || contentType.contains("text/plain"));
    }

    /** Request body after the extraBody merge; for golden tests. */
    public JsonObject requestBody(Context context, LlmRequestConfig cfg) {
        return prepareWithExtras(context, cfg).body();
    }

    public HttpCall prepareWithExtras(Context context, LlmRequestConfig cfg) {
        HttpCall call = prepare(context, cfg);
        mergeExtraBody(call.body(), cfg.extraBody());
        return call;
    }

    @Override
    public AgentMessage.Assistant stream(Context context, AbortSignal signal, Consumer<AssistantMessageEvent> sink) {
        LlmRequestConfig cfg;
        HttpCall prepared;
        try {
            cfg = config.get();
            prepared = prepareWithExtras(context, cfg);
        } catch (RuntimeException ex) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Invalid model configuration: " + ex.getMessage());
        }
        return execute(prepared, cfg, signal, sink);
    }

    protected AgentMessage.Assistant execute(HttpCall prepared, LlmRequestConfig cfg, AbortSignal signal,
                                             Consumer<AssistantMessageEvent> sink) {
        byte[] bytes = GSON.toJson(prepared.body()).getBytes(StandardCharsets.UTF_8);
        P2SMod.LOGGER.info("LLM request -> api={}, url={}, model={}, timeout={}s, stream={}, bytes={}",
                cfg.api().id(), redactUrl(prepared.url()), cfg.model(), cfg.timeoutSeconds(), prepared.stream(),
                bytes.length);
        Request request;
        try {
            HttpUrl url = HttpUrl.get(prepared.url());
            Request.Builder builder = new Request.Builder().url(url).post(RequestBody.create(bytes, JSON));
            prepared.headers().forEach(builder::header);
            cfg.headers().forEach(builder::header);
            authorize(builder, url, bytes, cfg);
            request = builder.build();
        } catch (RuntimeException ex) {
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Invalid request: " + ex.getMessage());
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
                    return AgentMessage.Assistant.failure(StopReason.ERROR, describeHttpError(response, error));
                }
                if (responseBody == null) {
                    return AgentMessage.Assistant.failure(StopReason.ERROR, "Empty response body");
                }
                String contentType = response.header("Content-Type", "");
                AgentMessage.Assistant result;
                if (prepared.stream() && isStreamResponse(contentType)) {
                    try (InputStream in = responseBody.byteStream()) {
                        result = parseStream(in, sink, signal, cfg);
                    }
                } else {
                    String text = responseBody.string();
                    result = parseResponse(text, sink, cfg);
                }
                if (signal.isAborted() && !result.failed()) {
                    result = new AgentMessage.Assistant(result.text(), result.thinking(), java.util.List.of(),
                            StopReason.ABORTED, "Operation aborted", result.timestamp());
                }
                logResult(result);
                return result;
            }
        } catch (IOException | RuntimeException ex) {
            if (signal.isAborted() || call.isCanceled()) {
                return AgentMessage.Assistant.failure(StopReason.ABORTED, "Operation aborted");
            }
            if (ex instanceof InterruptedIOException) {
                return AgentMessage.Assistant.failure(StopReason.ERROR, "Timed out after " + cfg.timeoutSeconds() + "s");
            }
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            P2SMod.LOGGER.warn("LLM request error: {}", message);
            return AgentMessage.Assistant.failure(StopReason.ERROR, "Request failed: " + message);
        }
    }

    private static void logResult(AgentMessage.Assistant result) {
        AgentMessage.Usage usage = result.usage();
        P2SMod.LOGGER.info("LLM response -> stop={}, toolCalls={}, textLen={}, thinkingLen={}{}{}",
                result.stopReason(), result.toolCalls().size(), result.text().length(), result.thinking().length(),
                usage.isEmpty() ? "" : ", usage(in=" + usage.input() + ", out=" + usage.output() + ", cacheRead="
                        + usage.cacheRead() + ", cacheWrite=" + usage.cacheWrite() + ", reasoning=" + usage.reasoning() + ")",
                result.errorMessage().isBlank() ? "" : ", error=" + result.errorMessage());
    }

    protected String describeHttpError(Response response, String body) {
        String detail = errorMessage(body);
        return "HTTP " + response.code() + (detail.isBlank() ? "" : ": " + truncate(detail, 400));
    }

    /** Best-effort error text from the common JSON error shapes; falls back to the raw body. */
    static String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonArray() && !parsed.getAsJsonArray().isEmpty()) {
                parsed = parsed.getAsJsonArray().get(0);
            }
            if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                JsonElement error = root.has("error") ? root.get("error") : root;
                if (error.isJsonObject()) {
                    JsonObject object = error.getAsJsonObject();
                    for (String key : new String[]{"message", "Message", "detail"}) {
                        String text = Json.string(object, key);
                        if (!text.isBlank()) {
                            String type = Json.string(object, "type");
                            return type.isBlank() || "error".equals(type) ? text : type + ": " + text;
                        }
                    }
                } else if (error.isJsonPrimitive()) {
                    return error.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON: keep the raw text.
        }
        return body.trim();
    }

    static void mergeExtraBody(JsonObject body, JsonObject extra) {
        for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                body.remove(entry.getKey());
            } else {
                body.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    static OkHttpClient client(int timeoutSeconds) {
        return CLIENTS.computeIfAbsent(timeoutSeconds, seconds -> new OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(Duration.ofSeconds(seconds))
                .readTimeout(Duration.ofSeconds(seconds))
                .writeTimeout(Duration.ofSeconds(seconds))
                .callTimeout(Duration.ofSeconds(seconds))
                .build());
    }

    /** Drops the query string, which may carry an API key (Gemini / Vertex {@code ?key=}). */
    static String redactUrl(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query) + "?…";
    }

    static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "...(len=" + text.length() + ")";
    }
}
