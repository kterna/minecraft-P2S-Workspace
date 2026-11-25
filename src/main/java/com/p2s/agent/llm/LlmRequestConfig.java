package com.p2s.agent.llm;

/**
 * Connection settings for one provider request. Optional sampling fields are null when the
 * user did not set them; null fields are never sent.
 */
public record LlmRequestConfig(
        String apiUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        boolean useToolCall,
        boolean streaming,
        Double temperature,
        Integer maxTokens
) {
    public LlmRequestConfig {
        apiUrl = apiUrl == null ? "" : apiUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        maxTokens = maxTokens == null || maxTokens <= 0 ? null : maxTokens;
    }

    public LlmRequestConfig(String apiUrl, String apiKey, String model, int timeoutSeconds,
                            boolean useToolCall, boolean streaming) {
        this(apiUrl, apiKey, model, timeoutSeconds, useToolCall, streaming, null, null);
    }

    public LlmRequestConfig withTimeoutSeconds(int seconds) {
        return new LlmRequestConfig(apiUrl, apiKey, model, seconds, useToolCall, streaming, temperature, maxTokens);
    }

    public LlmRequestConfig withStreaming(boolean value) {
        return new LlmRequestConfig(apiUrl, apiKey, model, timeoutSeconds, useToolCall, value, temperature, maxTokens);
    }

    /** Official OpenAI endpoints want max_completion_tokens; most compatible servers only know max_tokens. */
    public String maxTokensField() {
        return apiUrl.contains("api.openai.com") ? "max_completion_tokens" : "max_tokens";
    }

    /** DeepSeek rejects replayed assistant turns without reasoning_content while thinking is on. */
    public boolean requiresReasoningContent() {
        return apiUrl.toLowerCase(java.util.Locale.ROOT).contains("deepseek.com");
    }

    @Override
    public String toString() {
        return "LlmRequestConfig[apiUrl=" + apiUrl + ", model=" + model + ", timeoutSeconds=" + timeoutSeconds
                + ", useToolCall=" + useToolCall + ", streaming=" + streaming + ", temperature=" + temperature
                + ", maxTokens=" + maxTokens + "]";
    }
}
