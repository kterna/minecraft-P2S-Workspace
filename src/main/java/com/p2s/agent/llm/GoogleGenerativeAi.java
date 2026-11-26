package com.p2s.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AssistantMessageEvent;
import okhttp3.HttpUrl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Gemini Developer API and Vertex use the same generateContent request/response schema. */
public final class GoogleGenerativeAi extends HttpTransport {
    public GoogleGenerativeAi(Supplier<LlmRequestConfig> config) {
        super(config);
    }

    @Override
    protected HttpCall prepare(Context context, LlmRequestConfig cfg) {
        boolean vertex = cfg.api() == LlmApi.GOOGLE_VERTEX;
        String url = cfg.apiUrl().isBlank() && !vertex
                ? "https://generativelanguage.googleapis.com/v1beta" : cfg.apiUrl();
        HttpUrl base = HttpUrl.get(url);
        String method = cfg.streaming() ? ":streamGenerateContent" : ":generateContent";
        String path = base.encodedPath().replaceFirst("/+$", "").replaceFirst(":(streamGenerateContent|generateContent)$", "");
        HttpUrl.Builder endpoint = base.newBuilder().encodedPath(path.isEmpty() ? "/" : path);
        if (!path.contains("/models/")) {
            if (!path.endsWith("/models")) endpoint.addPathSegment("models");
            endpoint.addPathSegment(cfg.model());
        }
        HttpUrl target = endpoint.build();
        endpoint.encodedPath(target.encodedPath() + method).removeAllQueryParameters("alt");
        if (cfg.streaming()) endpoint.addQueryParameter("alt", "sse");
        Map<String, String> headers = vertex && !"apiKey".equalsIgnoreCase(cfg.option("auth"))
                ? Map.of("Authorization", "Bearer " + cfg.apiKey()) : Map.of("x-goog-api-key", cfg.apiKey());
        return new HttpCall(endpoint.build().toString(), headers, request(context, cfg), cfg.streaming());
    }

    private JsonObject request(Context context, LlmRequestConfig cfg) {
        JsonObject body = new JsonObject();
        if (!context.systemPrompt().isBlank()) {
            body.add("systemInstruction", content("", List.of(textPart(context.systemPrompt()))));
        }
        JsonArray contents = new JsonArray();
        Map<String, String> ids = new HashMap<>();
        boolean needsId = cfg.model().toLowerCase(Locale.ROOT)
                .matches("^(claude-|gpt-oss-|gemini(?:-live)?-(?:[3-9]|[1-9][0-9]+)(?:[.-]|$)).*");
        for (AgentMessage message : context.messages()) {
            if (message instanceof AgentMessage.User user) {
                add(contents, "user", List.of(textPart(user.content())));
            } else if (message instanceof AgentMessage.Assistant assistant) {
                JsonObject replay = TransportSupport.replay(assistant, cfg);
                JsonArray parts = Json.array(replay, "parts");
                if (parts.isEmpty()) {
                    if (!assistant.text().isBlank()) parts.add(textPart(assistant.text()));
                    for (AgentMessage.ToolCall call : assistant.toolCalls()) {
                        JsonObject function = new JsonObject();
                        function.addProperty("name", call.name());
                        function.add("args", TransportSupport.arguments(call));
                        parts.add(wrap("functionCall", function));
                    }
                }
                int position = 0;
                for (JsonElement item : parts) {
                    JsonObject fn = Json.object(item.getAsJsonObject(), "functionCall");
                    if (fn == null || position >= assistant.toolCalls().size()) continue;
                    String sourceId = assistant.toolCalls().get(position++).id();
                    String wireId = Json.string(fn, "id");
                    if (wireId.isEmpty() && needsId) {
                        wireId = TransportSupport.safeToolId(sourceId);
                        fn.addProperty("id", wireId);
                    }
                    if (!wireId.isEmpty()) ids.put(sourceId, wireId);
                }
                add(contents, "model", parts);
            } else if (message instanceof AgentMessage.ToolResult result) {
                JsonObject response = new JsonObject();
                response.addProperty("name", result.toolName());
                if (ids.containsKey(result.toolCallId())) response.addProperty("id", ids.get(result.toolCallId()));
                response.add("response", wrap(result.isError() ? "error" : "output", result.content()));
                add(contents, "user", List.of(wrap("functionResponse", response)));
            }
        }
        body.add("contents", contents);
        if (cfg.useToolCall() && !context.tools().isEmpty()) {
            JsonArray declarations = new JsonArray();
            for (TransportSupport.ToolSpec spec : TransportSupport.toolSpecs(context.tools())) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", spec.name());
                fn.addProperty("description", spec.description());
                fn.add("parametersJsonSchema", spec.parameters());
                declarations.add(fn);
            }
            JsonArray tools = new JsonArray();
            tools.add(wrap("functionDeclarations", declarations));
            body.add("tools", tools);
        }
        JsonObject generation = new JsonObject();
        if (cfg.maxTokens() != null) generation.addProperty("maxOutputTokens", cfg.maxTokens());
        if (cfg.temperature() != null) generation.addProperty("temperature", cfg.temperature());
        if (cfg.topP() != null) generation.addProperty("topP", cfg.topP());
        ThinkingLevel thinking = cfg.thinking();
        if (thinking != null || cfg.thinkingBudget() != null) {
            JsonObject options = new JsonObject();
            String model = cfg.model().toLowerCase(Locale.ROOT);
            String mode = cfg.compatString("thinkingMode");
            boolean levels = mode == null ? model.matches(".*gemini-([3-9]|[1-9][0-9]+).*") : mode.equals("level");
            if (levels && thinking != null) {
                String level = switch (thinking) {
                    case OFF, MINIMAL -> model.contains("pro") ? "LOW" : "MINIMAL";
                    case LOW -> "LOW";
                    case MEDIUM -> model.contains("gemini-3-pro") ? "HIGH" : "MEDIUM";
                    default -> "HIGH";
                };
                options.addProperty("thinkingLevel", level);
            } else {
                options.addProperty("thinkingBudget", thinking == ThinkingLevel.OFF ? 0
                        : cfg.thinkingBudget() != null ? cfg.thinkingBudget() : thinking.defaultBudget());
            }
            options.addProperty("includeThoughts", thinking != ThinkingLevel.OFF);
            generation.add("thinkingConfig", options);
        }
        if (!generation.isEmpty()) body.add("generationConfig", generation);
        return body;
    }

    private static JsonObject wrap(String key, JsonElement value) {
        JsonObject object = new JsonObject();
        object.add(key, value);
        return object;
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        return part;
    }

    private static JsonObject content(String role, Iterable<? extends JsonElement> parts) {
        JsonArray array = new JsonArray();
        parts.forEach(array::add);
        JsonObject content = wrap("parts", array);
        if (!role.isEmpty()) content.addProperty("role", role);
        return content;
    }

    private static void add(JsonArray contents, String role, Iterable<? extends JsonElement> parts) {
        JsonObject turn = content(role, parts);
        if (turn.getAsJsonArray("parts").isEmpty()) return;
        if (!contents.isEmpty()) {
            JsonObject last = contents.get(contents.size() - 1).getAsJsonObject();
            if (Json.string(last, "role").equals(role)) {
                last.getAsJsonArray("parts").addAll(turn.getAsJsonArray("parts"));
                return;
            }
        }
        contents.add(turn);
    }

    @Override
    protected AgentMessage.Assistant parseResponse(String body, Consumer<AssistantMessageEvent> sink, LlmRequestConfig cfg) {
        Candidate state = new Candidate();
        state.accept(body, sink);
        return state.finish(cfg);
    }

    @Override
    protected AgentMessage.Assistant parseStream(InputStream stream, Consumer<AssistantMessageEvent> sink,
                                                 AbortSignal signal, LlmRequestConfig cfg) throws IOException {
        Candidate state = new Candidate();
        SseDecoder.decode(new InputStreamReader(stream, StandardCharsets.UTF_8), (event, data) -> {
            state.accept(data, sink);
            return !signal.isAborted() && state.error.isEmpty();
        });
        if (signal.isAborted()) return AgentMessage.Assistant.failure(AgentMessage.StopReason.ABORTED, "Operation aborted");
        return state.finish(cfg);
    }

    private static final class Candidate {
        final TransportSupport.Reply reply = new TransportSupport.Reply();
        final JsonArray replay = new JsonArray();
        String reason = "";
        String error = "";

        void accept(String body, Consumer<AssistantMessageEvent> sink) {
            JsonObject root = Json.parseObject(body);
            if (root == null || root.has("error")) {
                error = errorMessage(body);
                if (error.isEmpty()) error = "Empty Gemini response";
                return;
            }
            JsonObject usage = Json.object(root, "usageMetadata");
            if (usage != null) {
                reply.usage = new AgentMessage.Usage(Json.number(usage, "promptTokenCount"), Json.number(usage, "candidatesTokenCount"),
                        Json.number(usage, "cachedContentTokenCount"), 0, Json.number(usage, "thoughtsTokenCount"));
            }
            String blocked = Json.string(Json.object(root, "promptFeedback"), "blockReason");
            if (!blocked.isEmpty()) error = "Gemini prompt blocked: " + blocked;
            for (JsonElement item : Json.array(root, "candidates")) {
                JsonObject candidate = item.getAsJsonObject();
                if (Json.number(candidate, "index") != 0) continue;
                String finish = Json.string(candidate, "finishReason");
                if (!finish.isEmpty()) reason = finish;
                for (JsonElement raw : Json.array(Json.object(candidate, "content"), "parts")) {
                    JsonObject part = raw.getAsJsonObject();
                    replay.add(part.deepCopy());
                    String text = Json.string(part, "text");
                    if (!text.isEmpty()) {
                        if (Json.bool(part, "thought")) {
                            reply.thinking.append(text);
                            sink.accept(new AssistantMessageEvent.ThinkingDelta(text));
                        } else {
                            reply.text.append(text);
                            sink.accept(new AssistantMessageEvent.TextDelta(text));
                        }
                    }
                    JsonObject fn = Json.object(part, "functionCall");
                    if (fn == null) continue;
                    String id = Json.string(fn, "id");
                    if (id.isBlank()) id = "call_" + UUID.randomUUID().toString().replace("-", "");
                    String name = Json.string(fn, "name");
                    JsonObject args = Json.object(fn, "args");
                    if (name.isBlank() || (args == null && fn.has("args")) || fn.has("partialArgs") || Json.bool(fn, "willContinue")) {
                        error = "Incomplete Gemini function call";
                        return;
                    }
                    if (args == null) args = new JsonObject();
                    int index = reply.calls.size();
                    reply.calls.add(AgentMessage.ToolCall.valid(id, name, args.toString(), args));
                    sink.accept(new AssistantMessageEvent.ToolCallStart(index, id, name));
                    sink.accept(new AssistantMessageEvent.ToolCallDelta(index, args.toString()));
                }
            }
        }

        AgentMessage.Assistant finish(LlmRequestConfig cfg) {
            if (error.isEmpty() && !reason.equals("STOP") && !reason.equals("MAX_TOKENS")) {
                error = reason.isEmpty() ? "Gemini response ended without a finishReason" : "Gemini stopped: " + reason;
            }
            if (error.isEmpty() && reason.equals("MAX_TOKENS") && !reply.calls.isEmpty()) error = "Gemini truncated a tool turn";
            if (error.isEmpty() && reply.calls.isEmpty() && reply.text.isEmpty()) error = "Gemini returned no answer or tool call";
            if (!error.isEmpty()) return AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, error);
            AgentMessage.StopReason stop = reason.equals("MAX_TOKENS") ? AgentMessage.StopReason.LENGTH
                    : reply.calls.isEmpty() ? AgentMessage.StopReason.STOP : AgentMessage.StopReason.TOOL_USE;
            return new AgentMessage.Assistant(reply.text.toString(), reply.thinking.toString(), reply.calls, stop, "",
                    System.currentTimeMillis(), TransportSupport.origin(cfg, wrap("parts", replay)), reply.usage);
        }
    }
}
