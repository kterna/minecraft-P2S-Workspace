package com.p2s.agent.llm;

import java.util.Locale;

/** Wire protocol families (pi: KnownApi). The id is what {@code p2s_client.json} stores. */
public enum LlmApi {
    OPENAI_COMPLETIONS("openai-completions", "OpenAI Chat Completions"),
    OPENAI_RESPONSES("openai-responses", "OpenAI Responses"),
    ANTHROPIC_MESSAGES("anthropic-messages", "Anthropic Messages"),
    GOOGLE_GENERATIVE_AI("google-generative-ai", "Gemini API"),
    GOOGLE_VERTEX("google-vertex", "Vertex AI"),
    BEDROCK_CONVERSE("bedrock-converse", "Bedrock Converse"),
    MISTRAL("mistral", "Mistral");

    private final String id;
    private final String label;

    LlmApi(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** Unknown or blank ids fall back to Chat Completions, the protocol of older configs. */
    public static LlmApi parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPENAI_COMPLETIONS;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (LlmApi api : values()) {
            if (api.id.equals(value)) {
                return api;
            }
        }
        return switch (value) {
            case "openai", "chat", "chat-completions", "openai-chat" -> OPENAI_COMPLETIONS;
            case "responses" -> OPENAI_RESPONSES;
            case "anthropic", "claude", "messages" -> ANTHROPIC_MESSAGES;
            case "gemini", "google" -> GOOGLE_GENERATIVE_AI;
            case "vertex" -> GOOGLE_VERTEX;
            case "bedrock", "bedrock-converse-stream" -> BEDROCK_CONVERSE;
            case "mistral-conversations", "mistral-chat" -> MISTRAL;
            default -> OPENAI_COMPLETIONS;
        };
    }

    public static boolean isKnown(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (LlmApi api : values()) {
            if (api.id.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public LlmApi next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
