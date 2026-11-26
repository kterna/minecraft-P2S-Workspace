package com.p2s.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolved settings for one provider request: connection, model and the optional parameters.
 * Every nullable parameter (thinking, temperature, topP, maxTokens, thinkingBudget) is null
 * when the user did not set it, and null parameters are never sent.
 *
 * @param provider        identity of the connection for signed thinking replay
 * @param headers         extra HTTP headers, applied after authentication headers
 * @param extraBody       merged into the request body last; a JSON null removes a field
 * @param compat          per-protocol overrides of detected behaviour (see docs/architecture/llm-transports.md)
 * @param providerOptions connection extras: region and AWS credentials (Bedrock), auth mode (Vertex)
 */
public record LlmRequestConfig(
        LlmApi api,
        String provider,
        String apiUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        boolean useToolCall,
        boolean streaming,
        ThinkingLevel thinking,
        Integer thinkingBudget,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Map<String, String> headers,
        JsonObject extraBody,
        JsonObject compat,
        JsonObject providerOptions
) {
    public LlmRequestConfig {
        api = api == null ? LlmApi.OPENAI_COMPLETIONS : api;
        provider = provider == null || provider.isBlank() ? "default" : provider.trim();
        apiUrl = apiUrl == null ? "" : apiUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        thinkingBudget = thinkingBudget == null || thinkingBudget <= 0 ? null : thinkingBudget;
        maxTokens = maxTokens == null || maxTokens <= 0 ? null : maxTokens;
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        extraBody = extraBody == null ? new JsonObject() : extraBody.deepCopy();
        compat = compat == null ? new JsonObject() : compat.deepCopy();
        providerOptions = providerOptions == null ? new JsonObject() : providerOptions.deepCopy();
    }

    /** Chat Completions configuration as written by older versions. */
    public LlmRequestConfig(String apiUrl, String apiKey, String model, int timeoutSeconds,
                            boolean useToolCall, boolean streaming) {
        this(apiUrl, apiKey, model, timeoutSeconds, useToolCall, streaming, null, null);
    }

    public LlmRequestConfig(String apiUrl, String apiKey, String model, int timeoutSeconds,
                            boolean useToolCall, boolean streaming, Double temperature, Integer maxTokens) {
        this(LlmApi.OPENAI_COMPLETIONS, "default", apiUrl, apiKey, model, timeoutSeconds, useToolCall, streaming,
                null, null, temperature, null, maxTokens, null, null, null, null);
    }

    @Override
    public JsonObject extraBody() {
        return extraBody.deepCopy();
    }

    @Override
    public JsonObject compat() {
        return compat.deepCopy();
    }

    @Override
    public JsonObject providerOptions() {
        return providerOptions.deepCopy();
    }

    /** An endpoint change invalidates opaque thinking blocks, even with the same model alias. */
    public static String connectionId(String url) {
        return "endpoint-" + TransportSupport.shortHash(url == null ? "" : url.trim());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public LlmRequestConfig withTimeoutSeconds(int seconds) {
        return toBuilder().timeoutSeconds(seconds).build();
    }

    public LlmRequestConfig withStreaming(boolean value) {
        return toBuilder().streaming(value).build();
    }

    public boolean thinkingEnabled() {
        return thinking != null && thinking.enabled();
    }

    /** String compat override, or null when absent. */
    public String compatString(String key) {
        return string(compat, key);
    }

    /** Boolean compat override, or null when absent. */
    public Boolean compatBool(String key) {
        JsonElement value = compat.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public String option(String key) {
        String value = string(providerOptions, key);
        return value == null ? "" : value;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            String text = value.getAsString().trim();
            return text.isEmpty() ? null : text;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Never prints the API key, AWS secrets or header values. */
    @Override
    public String toString() {
        return "LlmRequestConfig[api=" + api.id() + ", provider=" + provider + ", apiUrl=" + HttpTransport.redactUrl(apiUrl)
                + ", model=" + model + ", timeoutSeconds=" + timeoutSeconds + ", useToolCall=" + useToolCall
                + ", streaming=" + streaming + ", thinking=" + (thinking == null ? "unset" : thinking.id())
                + ", temperature=" + temperature + ", topP=" + topP + ", maxTokens=" + maxTokens + "]";
    }

    public static final class Builder {
        private LlmApi api;
        private String provider;
        private String apiUrl;
        private String apiKey;
        private String model;
        private int timeoutSeconds;
        private boolean useToolCall;
        private boolean streaming;
        private ThinkingLevel thinking;
        private Integer thinkingBudget;
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Map<String, String> headers;
        private JsonObject extraBody;
        private JsonObject compat;
        private JsonObject providerOptions;

        public Builder() {
            this(new LlmRequestConfig("", "", "", 60, true, true));
        }

        private Builder(LlmRequestConfig c) {
            api = c.api;
            provider = c.provider;
            apiUrl = c.apiUrl;
            apiKey = c.apiKey;
            model = c.model;
            timeoutSeconds = c.timeoutSeconds;
            useToolCall = c.useToolCall;
            streaming = c.streaming;
            thinking = c.thinking;
            thinkingBudget = c.thinkingBudget;
            temperature = c.temperature;
            topP = c.topP;
            maxTokens = c.maxTokens;
            headers = c.headers;
            extraBody = c.extraBody;
            compat = c.compat;
            providerOptions = c.providerOptions;
        }

        public Builder api(LlmApi value) { api = value; return this; }
        public Builder provider(String value) { provider = value; return this; }
        public Builder apiUrl(String value) { apiUrl = value; return this; }
        public Builder apiKey(String value) { apiKey = value; return this; }
        public Builder model(String value) { model = value; return this; }
        public Builder timeoutSeconds(int value) { timeoutSeconds = value; return this; }
        public Builder useToolCall(boolean value) { useToolCall = value; return this; }
        public Builder streaming(boolean value) { streaming = value; return this; }
        public Builder thinking(ThinkingLevel value) { thinking = value; return this; }
        public Builder thinkingBudget(Integer value) { thinkingBudget = value; return this; }
        public Builder temperature(Double value) { temperature = value; return this; }
        public Builder topP(Double value) { topP = value; return this; }
        public Builder maxTokens(Integer value) { maxTokens = value; return this; }
        public Builder headers(Map<String, String> value) { headers = value; return this; }
        public Builder extraBody(JsonObject value) { extraBody = value; return this; }
        public Builder compat(JsonObject value) { compat = value; return this; }
        public Builder providerOptions(JsonObject value) { providerOptions = value; return this; }

        public LlmRequestConfig build() {
            return new LlmRequestConfig(api, provider, apiUrl, apiKey, model, timeoutSeconds, useToolCall, streaming,
                    thinking, thinkingBudget, temperature, topP, maxTokens, headers, extraBody, compat, providerOptions);
        }
    }
}
