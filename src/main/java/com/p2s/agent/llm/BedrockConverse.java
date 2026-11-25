package com.p2s.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AssistantMessageEvent;
import okhttp3.HttpUrl;
import okhttp3.Request;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bedrock Runtime Converse and ConverseStream (binary AWS event stream). */
public final class BedrockConverse extends HttpTransport {
    public BedrockConverse(Supplier<LlmRequestConfig> config) { super(config); }

    @Override
    protected HttpCall prepare(Context context, LlmRequestConfig cfg) {
        String region = cfg.option("region");
        if (region.isBlank()) throw new IllegalArgumentException("Bedrock requires providerOptions.region");
        String address = cfg.apiUrl().isBlank() ? "https://bedrock-runtime." + region + ".amazonaws.com" : cfg.apiUrl();
        HttpUrl base = HttpUrl.get(address);
        HttpUrl.Builder url = base.newBuilder();
        String path = base.encodedPath().replaceFirst("/+$", "");
        if (path.endsWith("/converse") || path.endsWith("/converse-stream")) {
            url.encodedPath(path.replaceFirst("/converse(-stream)?$", ""));
        } else {
            url.encodedPath(path.isBlank() ? "/" : path).addPathSegment("model").addPathSegment(cfg.model());
        }
        url.addPathSegment(cfg.streaming() ? "converse-stream" : "converse");
        return new HttpCall(url.build().toString(), Map.of("Accept", cfg.streaming()
                ? "application/vnd.amazon.eventstream" : "application/json"), requestBodyFor(context, cfg), cfg.streaming());
    }

    @Override
    protected void authorize(Request.Builder request, HttpUrl url, byte[] body, LlmRequestConfig cfg) {
        String key = cfg.option("accessKeyId");
        String secret = cfg.option("secretAccessKey");
        if (!key.isEmpty() || !secret.isEmpty()) {
            if (key.isEmpty() || secret.isEmpty()) throw new IllegalArgumentException("Incomplete Bedrock signing credentials");
            BedrockSigner.sign(request, url, body, cfg.option("region"), key, secret, cfg.option("sessionToken"), Instant.now());
        } else {
            if (cfg.apiKey().isBlank()) throw new IllegalArgumentException("Bedrock requires a bearer token or SigV4 credentials");
            request.header("Authorization", "Bearer " + cfg.apiKey());
        }
    }

    @Override
    protected boolean isStreamResponse(String type) { return type.contains("application/vnd.amazon.eventstream"); }

    private static JsonObject field(String key, JsonElement value) {
        JsonObject object = new JsonObject();
        object.add(key, value);
        return object;
    }

    private static JsonObject text(String value) {
        JsonObject object = new JsonObject();
        object.addProperty("text", value);
        return object;
    }

    private static JsonArray array(JsonObject value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }

    private static void append(JsonArray messages, String role, JsonArray blocks) {
        if (blocks.isEmpty()) return;
        if (!messages.isEmpty()) {
            JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
            if (role.equals(Json.string(last, "role"))) {
                last.getAsJsonArray("content").addAll(blocks);
                return;
            }
        }
        JsonObject message = field("content", blocks);
        message.addProperty("role", role);
        messages.add(message);
    }

    private JsonObject requestBodyFor(Context context, LlmRequestConfig cfg) {
        JsonObject body = new JsonObject();
        if (!context.systemPrompt().isBlank()) body.add("system", array(text(context.systemPrompt())));
        JsonArray messages = new JsonArray();
        for (AgentMessage message : context.messages()) {
            if (message instanceof AgentMessage.User user) {
                append(messages, "user", array(text(user.content())));
            } else if (message instanceof AgentMessage.Assistant assistant) {
                JsonArray blocks = Json.array(TransportSupport.replay(assistant, cfg), "content");
                if (blocks.isEmpty()) {
                    if (!assistant.text().isBlank()) blocks.add(text(assistant.text()));
                    for (AgentMessage.ToolCall call : assistant.toolCalls()) {
                        JsonObject use = field("input", TransportSupport.arguments(call));
                        use.addProperty("name", call.name());
                        use.addProperty("toolUseId", TransportSupport.safeToolId(call.id()));
                        blocks.add(field("toolUse", use));
                    }
                }
                append(messages, "assistant", blocks);
            } else if (message instanceof AgentMessage.ToolResult result) {
                JsonObject block = field("content", array(field("json", result.content())));
                block.addProperty("toolUseId", TransportSupport.safeToolId(result.toolCallId()));
                if (result.isError() && cfg.model().contains("anthropic.")) block.addProperty("status", "error");
                append(messages, "user", array(field("toolResult", block)));
            }
        }
        body.add("messages", messages);
        if (cfg.useToolCall() && !context.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (TransportSupport.ToolSpec spec : TransportSupport.toolSpecs(context.tools())) {
                JsonObject tool = field("inputSchema", field("json", spec.parameters()));
                tool.addProperty("name", spec.name());
                if (!spec.description().isBlank()) tool.addProperty("description", spec.description());
                tools.add(field("toolSpec", tool));
            }
            body.add("toolConfig", field("tools", tools));
        }
        boolean claude = cfg.model().contains("anthropic.");
        boolean thinking = claude && cfg.thinkingEnabled();
        JsonObject inference = new JsonObject();
        if (!thinking && (!claude || AnthropicMessages.supportsTemperature(cfg))) {
            if (cfg.temperature() != null) inference.addProperty("temperature", cfg.temperature());
            if (cfg.topP() != null) inference.addProperty("topP", cfg.topP());
        }
        if (cfg.maxTokens() != null) inference.addProperty("maxTokens", cfg.maxTokens());
        if (claude && cfg.thinking() != null) {
            JsonObject extra = new JsonObject();
            JsonObject options = new JsonObject();
            if (!thinking) options.addProperty("type", "disabled");
            else if (AnthropicMessages.adaptiveThinking(cfg)) {
                options.addProperty("type", "adaptive");
                JsonObject output = new JsonObject();
                output.addProperty("effort", AnthropicMessages.effort(cfg.thinking(), cfg));
                extra.add("output_config", output);
            } else {
                int budget = cfg.thinkingBudget() == null ? cfg.thinking().defaultBudget() : cfg.thinkingBudget();
                if (budget < 1024 || (cfg.maxTokens() != null && cfg.maxTokens() <= budget))
                    throw new IllegalArgumentException("Bedrock Claude requires 1024 <= thinkingBudget < maxTokens");
                if (cfg.maxTokens() == null) inference.addProperty("maxTokens", Math.addExact(budget, 4096));
                options.addProperty("type", "enabled");
                options.addProperty("budget_tokens", budget);
            }
            extra.add("thinking", options);
            body.add("additionalModelRequestFields", extra);
        }
        if (!inference.isEmpty()) body.add("inferenceConfig", inference);
        return body;
    }

    @Override
    protected AgentMessage.Assistant parseResponse(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg) {
        JsonObject root = Json.parseObject(body);
        JsonObject message = Json.object(Json.object(root, "output"), "message");
        if (message == null) return AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, errorMessage(body));
        return finish(Json.array(message, "content"), Json.string(root, "stopReason"), Json.object(root, "usage"), cfg, sink);
    }

    private static AgentMessage.Assistant finish(JsonArray blocks, String reason, JsonObject usage, LlmRequestConfig cfg,
                                                  Consumer<AssistantMessageEvent> sink) {
        TransportSupport.Reply reply = new TransportSupport.Reply();
        for (JsonElement item : blocks) {
            JsonObject block = item.getAsJsonObject();
            String plain = Json.string(block, "text");
            String thought = Json.string(Json.object(Json.object(block, "reasoningContent"), "reasoningText"), "text");
            if (!plain.isEmpty()) { reply.text.append(plain); sink.accept(new AssistantMessageEvent.TextDelta(plain)); }
            if (!thought.isEmpty()) { reply.thinking.append(thought); sink.accept(new AssistantMessageEvent.ThinkingDelta(thought)); }
            JsonObject use = Json.object(block, "toolUse");
            if (use != null) {
                String id = Json.string(use, "toolUseId");
                String name = Json.string(use, "name");
                JsonObject args = Json.object(use, "input");
                if (id.isBlank() || name.isBlank() || args == null) return failure("Incomplete Bedrock tool call");
                int index = reply.calls.size();
                sink.accept(new AssistantMessageEvent.ToolCallStart(index, id, name));
                sink.accept(new AssistantMessageEvent.ToolCallDelta(index, args.toString()));
                reply.calls.add(AgentMessage.ToolCall.valid(id, name, args.toString(), args));
            }
        }
        AgentMessage.StopReason stop = switch (reason) {
            case "end_turn", "stop_sequence" -> AgentMessage.StopReason.STOP;
            case "tool_use" -> AgentMessage.StopReason.TOOL_USE;
            case "max_tokens", "model_context_window_exceeded" -> AgentMessage.StopReason.LENGTH;
            default -> AgentMessage.StopReason.ERROR;
        };
        if (stop == AgentMessage.StopReason.ERROR || (stop == AgentMessage.StopReason.TOOL_USE) != !reply.calls.isEmpty())
            return failure("Unexpected Bedrock stop reason: " + reason);
        if (reply.calls.isEmpty() && reply.text.isEmpty()) return failure("Bedrock returned no answer or tool call");
        return new AgentMessage.Assistant(reply.text.toString(), reply.thinking.toString(), reply.calls, stop, "",
                System.currentTimeMillis(), TransportSupport.origin(cfg, field("content", blocks)),
                new AgentMessage.Usage(Json.number(usage, "inputTokens"), Json.number(usage, "outputTokens"),
                        Json.number(usage, "cacheReadInputTokens"), Json.number(usage, "cacheWriteInputTokens"), 0));
    }

    private static AgentMessage.Assistant failure(String reason) {
        return AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, reason);
    }

    @Override
    protected AgentMessage.Assistant parseStream(InputStream body, Consumer<AssistantMessageEvent> sink,
                                                 AbortSignal signal, LlmRequestConfig cfg) throws IOException {
        TreeMap<Integer, Block> blocks = new TreeMap<>();
        String reason = "";
        JsonObject usage = null;
        BedrockEventStream.Event event;
        while (!signal.isAborted() && (event = BedrockEventStream.read(body)) != null) {
            JsonObject data = event.payload();
            if (event.error()) return failure(event.type() + ": " + Json.string(data, "message"));
            int index = (int) Json.number(data, "contentBlockIndex");
            switch (event.type()) {
                case "contentBlockStart" -> {
                    Block block = new Block();
                    if (blocks.putIfAbsent(index, block) != null) return failure("Duplicate Bedrock block start");
                    block.tool = Json.object(Json.object(data, "start"), "toolUse");
                    if (block.tool != null) sink.accept(new AssistantMessageEvent.ToolCallStart(index,
                            Json.string(block.tool, "toolUseId"), Json.string(block.tool, "name")));
                }
                case "contentBlockDelta" -> {
                    Block block = blocks.computeIfAbsent(index, key -> new Block());
                    if (block.done) return failure("Bedrock delta after block stop");
                    JsonObject delta = Json.object(data, "delta");
                    String fragment = Json.string(delta, "text");
                    if (!fragment.isEmpty()) {
                        block.text.append(fragment);
                        sink.accept(new AssistantMessageEvent.TextDelta(fragment));
                    }
                    String arguments = Json.string(Json.object(delta, "toolUse"), "input");
                    if (!arguments.isEmpty()) {
                        if (block.tool == null) return failure("Bedrock tool delta without a start");
                        block.args.append(arguments);
                        sink.accept(new AssistantMessageEvent.ToolCallDelta(index, arguments));
                    }
                    JsonObject reasoning = Json.object(delta, "reasoningContent");
                    if (reasoning != null) {
                        String thought = Json.string(reasoning, "text");
                        block.thinking.append(thought);
                        block.signature.append(Json.string(reasoning, "signature"));
                        String redacted = Json.string(reasoning, "redactedContent");
                        if (!redacted.isEmpty()) block.redacted.writeBytes(Base64.getDecoder().decode(redacted));
                        if (!thought.isEmpty()) sink.accept(new AssistantMessageEvent.ThinkingDelta(thought));
                    }
                }
                case "contentBlockStop" -> {
                    Block block = blocks.get(index);
                    if (block == null || block.done) return failure("Unexpected Bedrock block stop");
                    block.done = true;
                }
                case "messageStop" -> reason = Json.string(data, "stopReason");
                case "metadata" -> usage = Json.object(data, "usage");
                default -> { /* messageStart and future informational events */ }
            }
        }
        if (signal.isAborted()) return AgentMessage.Assistant.failure(AgentMessage.StopReason.ABORTED, "Operation aborted");
        JsonArray content = new JsonArray();
        for (Block block : blocks.values()) {
            if (!block.done) return failure("Incomplete Bedrock content block");
            content.add(block.materialize());
        }
        return finish(content, reason, usage, cfg, ignored -> {});
    }

    private static final class Block {
        final StringBuilder text = new StringBuilder(), thinking = new StringBuilder(), signature = new StringBuilder(),
                args = new StringBuilder();
        final ByteArrayOutputStream redacted = new ByteArrayOutputStream();
        JsonObject tool;
        boolean done;

        JsonObject materialize() {
            if (tool != null) {
                tool.add("input", Json.parseObject(args.isEmpty() ? "{}" : args.toString()));
                return field("toolUse", tool);
            }
            JsonObject reasoning = new JsonObject();
            if (redacted.size() > 0) reasoning.addProperty("redactedContent", Base64.getEncoder().encodeToString(redacted.toByteArray()));
            else if (!thinking.isEmpty() || !signature.isEmpty()) {
                JsonObject signedText = text(thinking.toString());
                if (!signature.isEmpty()) signedText.addProperty("signature", signature.toString());
                reasoning.add("reasoningText", signedText);
            }
            return reasoning.isEmpty() ? text(text.toString()) : field("reasoningContent", reasoning);
        }
    }
}
