package com.p2s.screen;

import com.p2s.screen.widget.P2SFlatButton;
import com.p2s.screen.widget.ThemePaint;

import static com.p2s.screen.widget.ThemePaint.color;

import com.p2s.ClientAgentManager;
import com.p2s.ClientSessionState;
import com.p2s.P2SI18n;
import com.p2s.compat.P2SGfx;
import com.p2s.compat.P2SScreenBase;
import com.p2s.store.SessionPersistence;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.p2s.compat.P2SInputCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class P2SSessionListScreen extends P2SScreenBase {
    private static final int VISIBLE_ROWS = 10;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 50;

    private final Screen parent;
    private List<SessionPersistence.SessionIndexEntry> sessions = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Button> loadButtons = new ArrayList<>();
    private final List<Button> deleteButtons = new ArrayList<>();
    private int scroll = 0;
    private Component statusText = Component.empty();
    private String statusColor = "muted";
    private String projectId = "";
    private String projectName = "";

    public P2SSessionListScreen(Screen parent) {
        super(P2SI18n.tr("screen.p2s.sessions.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        projectId = ClientSessionState.getProjectId();
        projectName = ClientSessionState.getProjectName();
        sessions = projectId == null || projectId.isBlank()
                ? new ArrayList<>()
                : SessionPersistence.listSessions(projectId);
        statusText = projectId == null || projectId.isBlank() ? P2SI18n.tr("screen.p2s.sessions.status.open_project_first") : Component.empty();
        statusColor = projectId == null || projectId.isBlank() ? "warning" : "muted";

        clearWidgets();
        rowButtons.clear();
        loadButtons.clear();
        deleteButtons.clear();

        int panelWidth = Math.min(640, this.width - 40);
        int left = (this.width - panelWidth) / 2;
        int top = 40;
        int listWidth = panelWidth - BUTTON_WIDTH * 2 - 16;

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int rowY = top + i * (ROW_HEIGHT + 2);
            final int row = i;

            Button rowBtn = P2SFlatButton.themed(Component.empty(), btn -> {})
                    .bounds(left, rowY, listWidth, ROW_HEIGHT)
                    .build();
            rowBtn.active = false;
            rowButtons.add(rowBtn);
            addRenderableWidget(rowBtn);

            Button loadBtn = P2SFlatButton.themed(P2SI18n.tr("screen.p2s.common.open"), btn -> loadSession(row))
                    .bounds(left + listWidth + 4, rowY, BUTTON_WIDTH, ROW_HEIGHT)
                    .build();
            loadButtons.add(loadBtn);
            addRenderableWidget(loadBtn);

            Button deleteBtn = P2SFlatButton.themed(P2SI18n.tr("screen.p2s.workspace.delete_short"), btn -> deleteSession(row))
                    .bounds(left + listWidth + 4 + BUTTON_WIDTH + 4, rowY, BUTTON_WIDTH, ROW_HEIGHT)
                    .build();
            deleteButtons.add(deleteBtn);
            addRenderableWidget(deleteBtn);
        }

        int bottomY = top + VISIBLE_ROWS * (ROW_HEIGHT + 2) + 4;
        addRenderableWidget(P2SFlatButton.themed(P2SI18n.tr("screen.p2s.common.up"), btn -> {
            if (scroll > 0) {
                scroll--;
                refreshRows();
            }
        }).bounds(left, bottomY, 50, 20).build());

        addRenderableWidget(P2SFlatButton.themed(P2SI18n.tr("screen.p2s.common.down"), btn -> {
            int maxScroll = Math.max(0, sessions.size() - VISIBLE_ROWS);
            if (scroll < maxScroll) {
                scroll++;
                refreshRows();
            }
        }).bounds(left + 56, bottomY, 60, 20).build());

        addRenderableWidget(P2SFlatButton.themed(P2SI18n.tr("screen.p2s.sessions.new_session"), btn -> {
            ClientAgentManager.newSession();
            onClose();
        }).bounds(left + 124, bottomY, 90, 20).build());

        addRenderableWidget(P2SFlatButton.themed(P2SI18n.tr("screen.p2s.common.close"), btn -> {
            ClientAgentManager.closeCurrentSession();
            onClose();
        }).bounds(left + 220, bottomY, 60, 20).build());

        addRenderableWidget(P2SFlatButton.themed(P2SI18n.tr("screen.p2s.common.back"), btn -> onClose())
                .bounds(left + panelWidth - 60, bottomY, 60, 20).build());

        refreshRows();
    }

    private void refreshRows() {
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = scroll + i;
            Button rowBtn = rowButtons.get(i);
            Button loadBtn = loadButtons.get(i);
            Button deleteBtn = deleteButtons.get(i);

            if (idx >= 0 && idx < sessions.size()) {
                SessionPersistence.SessionIndexEntry entry = sessions.get(idx);
                P2SFlatButton.setSelected(rowBtn, entry.id().equals(ClientSessionState.getSessionId()));
                String title = entry.title();
                if (title.length() > 40) {
                    title = title.substring(0, 37) + "...";
                }
                String time = formatTime(entry.updatedAt());
                rowBtn.setMessage(P2SI18n.tr("screen.p2s.sessions.row", title, entry.messageCount(), time));
                rowBtn.visible = true;
                loadBtn.visible = true;
                loadBtn.active = true;
                deleteBtn.visible = true;
                deleteBtn.active = true;
            } else {
                rowBtn.setMessage(Component.empty());
                rowBtn.visible = false;
                loadBtn.visible = false;
                loadBtn.active = false;
                deleteBtn.visible = false;
                deleteBtn.active = false;
            }
        }
    }

    private void loadSession(int row) {
        int idx = scroll + row;
        if (idx < 0 || idx >= sessions.size()) {
            return;
        }
        SessionPersistence.SessionIndexEntry entry = sessions.get(idx);
        ClientAgentManager.restoreSession(entry.id());
        statusText = P2SI18n.tr("screen.p2s.sessions.status.loaded", entry.title());
        statusColor = "success";
        onClose();
    }

    private void deleteSession(int row) {
        int idx = scroll + row;
        if (idx < 0 || idx >= sessions.size()) {
            return;
        }
        SessionPersistence.SessionIndexEntry entry = sessions.get(idx);
        boolean ok = SessionPersistence.deleteSession(entry.id());
        if (ok) {
            sessions = projectId == null || projectId.isBlank()
                    ? new ArrayList<>()
                    : SessionPersistence.listSessions(projectId);
            statusText = P2SI18n.tr("screen.p2s.sessions.status.deleted");
            statusColor = "success";
        } else {
            statusText = P2SI18n.tr("screen.p2s.sessions.status.delete_failed");
            statusColor = "danger";
        }
        refreshRows();
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "?";
        }
        try {
            return new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    public void p2sRenderBackground(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        gfx.fill(0, 0, this.width, this.height, color("backdrop"));
    }

    @Override
    public void p2sRender(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        p2sSuperRender(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(640, this.width - 40);
        int left = (this.width - panelWidth) / 2;
        String headerProject = projectName == null || projectName.isBlank() ? P2SI18n.tr("screen.p2s.sessions.no_project").getString() : projectName;
        ThemePaint.text(gfx, this.font, P2SI18n.tr("screen.p2s.sessions.header", headerProject, sessions.size()), left, 24, color("text"), true);

        if (statusText != null && !statusText.getString().isBlank()) {
            ThemePaint.text(gfx, this.font, statusText, left, this.height - 20, color(statusColor), false);
        }
    }

    @Override
    public boolean p2sKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == P2SInputCompat.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return p2sSuperKeyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            com.p2s.compat.P2SClientLifecycleCompat.setScreen(this.minecraft, parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
