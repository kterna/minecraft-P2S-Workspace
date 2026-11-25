package com.p2s.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.StreamFunction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Selects the transport for the configured protocol on every request, so config edits apply immediately. */
public final class LlmTransports {
    private static final Gson GSON = new Gson();

    private LlmTransports() {
    }

    public static StreamFunction stream(Supplier<LlmRequestConfig> config) {
        return (context, signal, sink) -> {
            LlmRequestConfig cfg;
            try {
                cfg = config.get();
            } catch (RuntimeException ex) {
                return AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR,
                        "Invalid model configuration: " + ex.getMessage());
            }
            return create(cfg).stream(context, signal, sink);
        };
    }

    public static HttpTransport create(LlmRequestConfig cfg) {
        Supplier<LlmRequestConfig> fixed = () -> cfg;
        return switch (cfg.api()) {
            case OPENAI_COMPLETIONS, MISTRAL -> new OpenAiChatCompletions(fixed);
            case OPENAI_RESPONSES -> new OpenAiResponses(fixed);
            case ANTHROPIC_MESSAGES -> new AnthropicMessages(fixed);
            case GOOGLE_GENERATIVE_AI, GOOGLE_VERTEX -> new GoogleGenerativeAi(fixed);
            case BEDROCK_CONVERSE -> new BedrockConverse(fixed);
        };
    }

    /**
     * One-shot plain-text completion over persisted history (history compaction). Throws on
     * failure because callers fall back to keeping the uncompacted history. Chat Completions
     * keeps the tool-call structure as before; the other protocols get the history as plain text
     * because no tools are declared on this request and several of them reject tool blocks then.
     */
    public static String completeText(List<JsonObject> wireMessages, LlmRequestConfig cfg, AbortSignal signal)
            throws IOException {
        LlmRequestConfig plain = cfg.toBuilder().streaming(false).useToolCall(false).build();
        AgentMessage.Assistant result = create(plain).stream(completionContext(wireMessages, plain), signal, event -> { });
        if (result.failed()) {
            throw new IOException(result.errorMessage());
        }
        return result.text();
    }

    static StreamFunction.Context completionContext(List<JsonObject> wireMessages, LlmRequestConfig cfg) {
        OpenAiMessageCodec.Decoded decoded = OpenAiMessageCodec.fromWire(wireMessages);
        boolean chat = cfg.api() == LlmApi.OPENAI_COMPLETIONS || cfg.api() == LlmApi.MISTRAL;
        List<AgentMessage> messages = chat ? decoded.messages() : flatten(decoded.messages());
        return new StreamFunction.Context(decoded.systemPrompt(), messages, List.of());
    }

    /** Tool calls and results as text, consecutive user turns merged; replay data dropped. */
    static List<AgentMessage> flatten(List<AgentMessage> messages) {
        List<AgentMessage> out = new ArrayList<>();
        for (AgentMessage message : messages) {
            String text;
            boolean user;
            if (message instanceof AgentMessage.User u) {
                text = u.content();
                user = true;
            } else if (message instanceof AgentMessage.ToolResult result) {
                text = "[tool result " + result.toolName() + "]\n" + GSON.toJson(result.content());
                user = true;
            } else if (message instanceof AgentMessage.Assistant assistant) {
                StringBuilder builder = new StringBuilder(assistant.text());
                for (AgentMessage.ToolCall call : assistant.toolCalls()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append("[tool call ").append(call.name()).append("] ").append(TransportSupport.argumentsText(call));
                }
                text = builder.toString();
                user = false;
            } else {
                continue;
            }
            if (text.isBlank()) {
                continue;
            }
            AgentMessage last = out.isEmpty() ? null : out.getLast();
            if (user && last instanceof AgentMessage.User previous) {
                out.set(out.size() - 1, new AgentMessage.User(previous.content() + "\n\n" + text, previous.timestamp()));
            } else if (!user && last instanceof AgentMessage.Assistant previous) {
                out.set(out.size() - 1, new AgentMessage.Assistant(previous.text() + "\n\n" + text, "", List.of(),
                        AgentMessage.StopReason.STOP, "", previous.timestamp()));
            } else if (user) {
                out.add(new AgentMessage.User(text, message.timestamp()));
            } else {
                out.add(new AgentMessage.Assistant(text, "", List.of(), AgentMessage.StopReason.STOP, "", message.timestamp()));
            }
        }
        return out;
    }
}
