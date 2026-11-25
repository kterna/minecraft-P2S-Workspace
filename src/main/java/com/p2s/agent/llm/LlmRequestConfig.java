package com.p2s.agent.llm;

/** Connection settings for one provider request. */
public record LlmRequestConfig(
        String apiUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        boolean useToolCall,
        boolean streaming
) {
    public LlmRequestConfig {
        apiUrl = apiUrl == null ? "" : apiUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
    }

    public LlmRequestConfig withTimeoutSeconds(int seconds) {
        return new LlmRequestConfig(apiUrl, apiKey, model, seconds, useToolCall, streaming);
    }

    public LlmRequestConfig withStreaming(boolean value) {
        return new LlmRequestConfig(apiUrl, apiKey, model, timeoutSeconds, useToolCall, value);
    }
}
