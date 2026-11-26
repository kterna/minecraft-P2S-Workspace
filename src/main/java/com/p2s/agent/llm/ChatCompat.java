package com.p2s.agent.llm;

import java.util.Locale;

/**
 * Behaviour switches for Chat Completions servers (pi: OpenAICompletionsCompat). Detected from
 * the URL / protocol through a preset, then overridden field by field from {@code compat}.
 *
 * @param maxTokensField          {@code max_tokens} or {@code max_completion_tokens}
 * @param thinkingFormat          how a thinking level is written: openai | deepseek | openrouter | qwen | mistral | none
 * @param usageInStreaming        send {@code stream_options.include_usage}
 * @param requiresReasoningContent every replayed assistant turn carries {@code reasoning_content} (DeepSeek)
 * @param echoReasoning           auto (same model only) | always | never
 * @param mistral                 Mistral wire quirks: 9-character tool ids, thinking as content chunks, tool result name
 */
public record ChatCompat(
        String preset,
        String maxTokensField,
        String thinkingFormat,
        boolean usageInStreaming,
        boolean requiresReasoningContent,
        boolean supportsTemperature,
        String echoReasoning,
        boolean mistral
) {
    public static ChatCompat resolve(LlmRequestConfig cfg) {
        String preset = cfg.compatString("preset");
        if (preset == null || "auto".equalsIgnoreCase(preset)) {
            preset = detectPreset(cfg);
        }
        preset = preset.toLowerCase(Locale.ROOT);
        ChatCompat base = switch (preset) {
            case "openai" -> new ChatCompat("openai", "max_completion_tokens", "openai", true, false, true, "auto", false);
            case "deepseek" -> new ChatCompat("deepseek", "max_tokens", "deepseek", true, true, true, "auto", false);
            case "openrouter" -> new ChatCompat("openrouter", "max_tokens", "openrouter", true, false, true, "auto", false);
            case "anthropic-via-gateway" -> new ChatCompat("anthropic-via-gateway", "max_tokens", "none", false, false, false, "auto", false);
            case "mistral" -> new ChatCompat("mistral", "max_tokens", "mistral", false, false, true, "auto", true);
            default -> new ChatCompat("generic", "max_tokens", "openai", false, false, true, "auto", false);
        };
        return new ChatCompat(
                base.preset,
                or(cfg.compatString("maxTokensField"), base.maxTokensField),
                or(cfg.compatString("thinkingFormat"), base.thinkingFormat).toLowerCase(Locale.ROOT),
                or(cfg.compatBool("supportsUsageInStreaming"), base.usageInStreaming),
                or(cfg.compatBool("requiresReasoningContent"), base.requiresReasoningContent),
                or(cfg.compatBool("supportsTemperature"), base.supportsTemperature),
                or(cfg.compatString("echoReasoning"), base.echoReasoning).toLowerCase(Locale.ROOT),
                base.mistral);
    }

    static String detectPreset(LlmRequestConfig cfg) {
        if (cfg.api() == LlmApi.MISTRAL) {
            return "mistral";
        }
        String url = cfg.apiUrl().toLowerCase(Locale.ROOT);
        if (url.contains("api.openai.com")) {
            return "openai";
        }
        if (url.contains("deepseek.com")) {
            return "deepseek";
        }
        if (url.contains("openrouter.ai")) {
            return "openrouter";
        }
        if (url.contains("api.mistral.ai")) {
            return "mistral";
        }
        return "generic";
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static boolean or(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
