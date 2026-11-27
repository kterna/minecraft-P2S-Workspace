package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientSessionSnapshotTest {
    @AfterEach
    void resetView() {
        ClientSessionState.resetAll();
    }

    @Test
    void capturedViewKeepsItsProjectMessagesPlanAndChoiceAfterSwitch() {
        openProject("project-a", "a.toml");
        ClientSessionState.addUserMessage("Build project A");
        ClientSessionState.setPlan("A plan", List.of(new ClientSessionState.PlanItem("A step", "in_progress")));
        ClientSessionState.setPendingChoice("choice-a", "A question",
                List.of(new ClientSessionState.ChoiceOption("a", "A option", "")));
        var saved = ClientSessionState.captureSessionView();

        ClientSessionState.resetAll();
        openProject("project-b", "b.toml");
        ClientSessionState.addUserMessage("Build project B");
        ClientSessionState.setPlan("B plan", List.of(new ClientSessionState.PlanItem("B step", "completed")));
        var current = ClientSessionState.captureSessionView();

        assertEquals("project-a", saved.projectId());
        assertEquals("a.toml", saved.selectedWorkspacePath());
        assertEquals("Build project A", saved.messages().getFirst().text());
        assertEquals("A plan", saved.planExplanation());
        assertEquals("A step", saved.planItems().getFirst().step());
        assertEquals("choice-a", saved.pendingChoice().requestId());
        assertEquals("project-b", current.projectId());
        assertEquals("Build project B", current.messages().getFirst().text());
        assertNull(current.pendingChoice());
    }

    @Test
    void capturedCollectionsCannotBeChangedByTheSaveWorker() {
        ClientSessionState.resetAll();
        ClientSessionState.addUserMessage("Retained message");
        ClientSessionState.setPlan("Plan", List.of(new ClientSessionState.PlanItem("Step", "pending")));
        ClientSessionState.setPendingChoice("choice", "Question",
                List.of(new ClientSessionState.ChoiceOption("a", "Option", "")));
        var saved = ClientSessionState.captureSessionView();

        assertThrows(UnsupportedOperationException.class, () -> saved.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> saved.planItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> saved.pendingChoice().options().clear());
        ClientSessionState.clearMessages();
        ClientSessionState.clearPlan();
        ClientSessionState.clearPendingChoice();
        assertEquals(1, saved.messages().size());
        assertEquals(1, saved.planItems().size());
        assertEquals(1, saved.pendingChoice().options().size());
    }

    @Test
    void lateEndReplyForThePreviousServerSessionKeepsTheRestoredConversation() {
        // Order seen in game: restore fills the view, then the old session's "ended" sync arrives,
        // then the restored session's "started" sync.
        openProject("project-a", "a.toml");
        ClientSessionState.addUserMessage("Restored request");
        ClientSessionState.setPlan("Restored plan", List.of(new ClientSessionState.PlanItem("Step", "in_progress")));
        ClientSessionState.setPendingChoice("choice", "Question",
                List.of(new ClientSessionState.ChoiceOption("a", "Option", "")));

        ClientSessionState.onSessionSync(true, false, "", "project-a", "Project", "",
                0, 0, 0, false, 0, 0, 0, "", 0, 0, "", "", "", "",
                false, "", "", "", 0, "[]", "", "[]");
        openProject("project-a", "a.toml");

        assertEquals("Restored request", ClientSessionState.getMessages().getFirst().text());
        assertEquals("Restored plan", ClientSessionState.getPlanExplanation());
        assertEquals(1, ClientSessionState.getPlanItems().size());
        assertEquals("choice", ClientSessionState.getPendingChoice().requestId());
    }

    private static void openProject(String projectId, String path) {
        JsonObject workspace = new JsonObject();
        workspace.addProperty("path", path);
        JsonArray workspaces = new JsonArray();
        workspaces.add(workspace);
        ClientSessionState.onSessionSync(true, true, "session", projectId, "Project", "",
                0, 0, 0, false, 0, 0, 0, path, 0, 0, "", "", "idle", "revision",
                false, "", "", "", 0, "[]", "", workspaces.toString());
    }
}
