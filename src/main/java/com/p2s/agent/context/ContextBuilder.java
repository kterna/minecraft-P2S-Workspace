package com.p2s.agent.context;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentLoop;
import com.p2s.agent.core.AgentMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Builds the model's view of a transcript (pi: transformContext). The stored transcript is
 * never modified here.
 *
 * <ol>
 *   <li>Failed assistant messages lose their tool calls and are dropped when empty.</li>
 *   <li>Every remaining tool call gets exactly one result directly after its assistant
 *       message; missing results are synthesized as "not executed", duplicates and orphans
 *       are dropped.</li>
 *   <li>Old turn groups are removed from the front until the view fits the message limit;
 *       pinned messages (compaction summaries) are always kept.</li>
 * </ol>
 */
public final class ContextBuilder {
    private ContextBuilder() {
    }

    public static List<AgentMessage> build(List<AgentMessage> transcript, int maxMessages,
                                           Predicate<AgentMessage> pinned) {
        return limit(repair(transcript), maxMessages, pinned);
    }

    /** Enforces the tool-call pairing invariants providers require. */
    public static List<AgentMessage> repair(List<AgentMessage> transcript) {
        List<AgentMessage> out = new ArrayList<>();
        int i = 0;
        while (i < transcript.size()) {
            AgentMessage message = transcript.get(i);
            if (message instanceof AgentMessage.ToolResult) {
                i++; // Orphan: not directly after an assistant with a matching call.
                continue;
            }
            if (!(message instanceof AgentMessage.Assistant assistant)) {
                out.add(message);
                i++;
                continue;
            }
            int next = i + 1;
            List<AgentMessage.ToolResult> following = new ArrayList<>();
            while (next < transcript.size() && transcript.get(next) instanceof AgentMessage.ToolResult result) {
                following.add(result);
                next++;
            }
            if (assistant.failed()) {
                if (!assistant.text().isBlank()) {
                    out.add(new AgentMessage.Assistant(assistant.text(), assistant.thinking(), List.of(),
                            AgentMessage.StopReason.STOP, "", assistant.timestamp()));
                }
                i = next;
                continue;
            }
            if (!assistant.hasToolCalls()) {
                // An assistant message with neither content nor tool calls is rejected by providers.
                if (!assistant.text().isBlank()) {
                    out.add(assistant);
                }
                i = next;
                continue;
            }
            out.add(assistant);
            Map<String, AgentMessage.ToolResult> byId = new LinkedHashMap<>();
            for (AgentMessage.ToolResult result : following) {
                byId.putIfAbsent(result.toolCallId(), result);
            }
            Set<String> answered = new HashSet<>();
            for (AgentMessage.ToolCall call : assistant.toolCalls()) {
                if (!answered.add(call.id())) {
                    continue;
                }
                AgentMessage.ToolResult result = byId.get(call.id());
                out.add(result != null ? result : missingResult(call));
            }
            i = next;
        }
        return out;
    }

    /**
     * Drops the oldest turn groups until at most {@code maxMessages} remain. A group starts at
     * a user message (or at the first message) and runs until the next user message, so an
     * assistant message is never separated from its tool results.
     */
    public static List<AgentMessage> limit(List<AgentMessage> messages, int maxMessages,
                                           Predicate<AgentMessage> pinned) {
        if (maxMessages <= 0 || messages.size() <= maxMessages) {
            return List.copyOf(messages);
        }
        List<List<AgentMessage>> groups = new ArrayList<>();
        List<AgentMessage> current = null;
        for (AgentMessage message : messages) {
            if (current == null || message instanceof AgentMessage.User) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(message);
        }
        List<AgentMessage> keptPinned = new ArrayList<>();
        int total = messages.size();
        int drop = 0;
        // Always keep the newest group, even if it alone exceeds the limit.
        while (total > maxMessages && drop < groups.size() - 1) {
            List<AgentMessage> group = groups.get(drop);
            for (AgentMessage message : group) {
                if (pinned != null && pinned.test(message)) {
                    keptPinned.add(message);
                } else {
                    total--;
                }
            }
            drop++;
        }
        List<AgentMessage> out = new ArrayList<>(keptPinned);
        for (int g = drop; g < groups.size(); g++) {
            out.addAll(groups.get(g));
        }
        return List.copyOf(out);
    }

    private static AgentMessage.ToolResult missingResult(AgentMessage.ToolCall call) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", call.name());
        payload.addProperty("error", "Not executed: no result was recorded for this call.");
        payload.addProperty("error_code", AgentLoop.NOT_EXECUTED);
        return new AgentMessage.ToolResult(call.id(), call.name(), payload, true, 0L);
    }
}
