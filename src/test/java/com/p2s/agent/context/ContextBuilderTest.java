package com.p2s.agent.context;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentLoop;
import com.p2s.agent.core.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextBuilderTest {
    private static AgentMessage.User user(String text) {
        return new AgentMessage.User(text, 0L);
    }

    private static AgentMessage.Assistant calls(String... ids) {
        List<AgentMessage.ToolCall> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(AgentMessage.ToolCall.valid(id, "read", "{}", new JsonObject()));
        }
        return new AgentMessage.Assistant("", "", calls, AgentMessage.StopReason.TOOL_USE, "", 0L);
    }

    private static AgentMessage.Assistant text(String text) {
        return new AgentMessage.Assistant(text, "", List.of(), AgentMessage.StopReason.STOP, "", 0L);
    }

    private static AgentMessage.ToolResult result(String id) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("id", id);
        return new AgentMessage.ToolResult(id, "read", payload, false, 0L);
    }

    private static List<String> shape(List<AgentMessage> messages) {
        return messages.stream().map(m -> switch (m) {
            case AgentMessage.User u -> "U:" + u.content();
            case AgentMessage.Assistant a -> a.hasToolCalls() ? "A:" + a.toolCalls().size() : "A:" + a.text();
            case AgentMessage.ToolResult r -> "R:" + r.toolCallId()
                    + (r.content().has("error_code") ? "!" + r.content().get("error_code").getAsString() : "");
        }).toList();
    }

    @Test
    void repairsPairingInCallOrder() {
        List<AgentMessage> transcript = List.of(
                result("orphan"),
                user("go"),
                calls("a", "b", "c"),
                result("c"), result("a"), result("a"), result("stranger"),
                user("next"));
        assertEquals(List.of("U:go", "A:3", "R:a", "R:b!" + AgentLoop.NOT_EXECUTED, "R:c", "U:next"),
                shape(ContextBuilder.repair(transcript)));
    }

    @Test
    void failedResponsesKeepTextButLoseToolCalls() {
        var failedWithText = new AgentMessage.Assistant("partial", "", List.of(
                AgentMessage.ToolCall.valid("x", "read", "{}", new JsonObject())),
                AgentMessage.StopReason.ABORTED, "aborted", 0L);
        var emptyFailure = AgentMessage.Assistant.failure(AgentMessage.StopReason.ERROR, "HTTP 500");
        var thinkingOnly = new AgentMessage.Assistant("", "hmm", List.of(), AgentMessage.StopReason.STOP, "", 0L);
        List<AgentMessage> transcript = List.of(user("go"), failedWithText, result("x"), emptyFailure, thinkingOnly,
                user("again"));
        assertEquals(List.of("U:go", "A:partial", "U:again"), shape(ContextBuilder.repair(transcript)));
    }

    @Test
    void limitDropsWholeGroupsAndKeepsPinnedSummaries() {
        List<AgentMessage> transcript = List.of(
                user("[summary]"), text("s"),
                user("one"), calls("a"), result("a"), text("1"),
                user("two"), calls("b"), result("b"), text("2"));
        List<AgentMessage> limited = ContextBuilder.limit(transcript, 5,
                m -> m instanceof AgentMessage.User u && u.content().startsWith("[summary]"));
        assertEquals(List.of("U:[summary]", "U:two", "A:1", "R:b", "A:2"), shape(limited));
    }

    @Test
    void limitAlwaysKeepsTheNewestGroup() {
        List<AgentMessage> transcript = List.of(user("old"), text("x"), user("new"), calls("a"), result("a"),
                calls("b"), result("b"), text("done"));
        List<AgentMessage> limited = ContextBuilder.limit(transcript, 2, null);
        assertEquals(List.of("U:new", "A:1", "R:a", "A:1", "R:b", "A:done"), shape(limited));
    }
}
