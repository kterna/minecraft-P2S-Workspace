package com.p2s;

import com.google.gson.JsonObject;
import com.p2s.compat.P2SEditBoxBase;
import com.p2s.compat.P2SGameCompat;
import com.p2s.compat.P2SGfx;
import com.p2s.compat.P2SInputCompat;
import com.p2s.compat.P2SScreenBase;
import com.p2s.preview.ClientPreviewStore;
import com.p2s.preview.OrbitCamera;
import com.p2s.screen.P2SCheckpointListScreen;
import com.p2s.screen.P2SConfigScreen;
import com.p2s.screen.P2SProjectListScreen;
import com.p2s.screen.P2SSessionListScreen;
import com.p2s.workbench.LineDiff;
import com.p2s.workbench.WbPalette;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Chat-first workbench (docs/proposals/ui-final-spec.md): a ~300 px panel on the left with
 * 对话 / 文件 / 审阅, the real world on the right with ghost previews and the orbit camera.
 * Drawn immediate-mode: every frame rebuilds the click regions, so layout follows state directly.
 */
public class P2SWorkbenchScreen extends P2SScreenBase {
    private enum Tab { CHAT, FILES, REVIEW }

    private static final int TOP = 20;
    private static final int TABS = 16;
    private static final int STATUS = 12;
    private static final int INPUT = 22;
    private static final int PAD = 6;
    private static final int LINE = 10;
    private static final int COLLAPSED = 16;
    private static final long HOVER_NANOS = 100_000_000L;
    private static final int DIFF_CONTEXT = 3;

    // Kept across openings so O returns to the same place.
    private static Tab lastTab = Tab.CHAT;
    private static boolean lastCollapsed;
    private static String draft = "";

    private Tab tab = lastTab;
    private boolean collapsed = lastCollapsed;
    private boolean menuOpen;
    private boolean diffOpen;
    private boolean discardOpen;
    private String appliedPath = "";
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, Float> hover = new HashMap<>();
    private final Map<String, Long> hoverStamp = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, String> texts = new HashMap<>();
    private final Set<String> loading = new HashSet<>();

    private double chatScroll;
    private boolean stickBottom = true;
    private int chatContentHeight;
    private double reviewScroll;
    private int reviewContentHeight;
    private double fileScroll;
    private int fileContentHeight;
    private String filePath = "";
    private long runStarted;

    private EditBox input;
    private EditBox reason;
    /** Vanilla hideGui before this screen hid the hotbar, crosshair and hand; null when untouched. */
    private Boolean previousHideGui;

    private record Hit(int x1, int y1, int x2, int y2, Runnable action) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    public P2SWorkbenchScreen() {
        super(P2SI18n.tr("screen.p2s.wb.title"));
    }

    // ------------------------------------------------------------------ geometry

    private int panelWidth() {
        if (collapsed) {
            return COLLAPSED;
        }
        if (width < 420) {
            return width;
        }
        return Math.max(244, Math.min(300, Math.round(width * 0.47f)));
    }

    private boolean overPanel(double x, double y) {
        return x < panelWidth() || y < TOP || y >= height - STATUS;
    }

    private int contentTop() {
        return TOP + TABS;
    }

    private int contentBottom() {
        return height - STATUS - (tab == Tab.FILES ? 0 : INPUT);
    }

    private static int c(String name) {
        return WbPalette.color(name);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void init() {
        if (previousHideGui == null && minecraft != null) {
            previousHideGui = com.p2s.compat.P2SClientLifecycleCompat.isHideGui(minecraft);
            com.p2s.compat.P2SClientLifecycleCompat.setHideGui(minecraft, true);
        }
        input = new P2SEditBoxBase(font, 0, 0, 10, 12, P2SI18n.tr("screen.p2s.wb.input"));
        input.setMaxLength(8000);
        input.setBordered(false);
        input.setValue(draft);
        input.setResponder(value -> draft = value);
        addRenderableWidget(input);
        reason = new P2SEditBoxBase(font, 0, 0, 10, 12, P2SI18n.tr("screen.p2s.wb.discard_reason"));
        reason.setMaxLength(500);
        reason.setBordered(false);
        addRenderableWidget(reason);
        layoutInputs();
        if (tab == Tab.CHAT && !collapsed) {
            setInitialFocus(input);
        }
    }

    private void layoutInputs() {
        int pw = panelWidth();
        boolean chatInput = !collapsed && tab != Tab.FILES && !(tab == Tab.REVIEW);
        input.visible = chatInput;
        input.active = chatInput;
        input.setX(PAD + 8);
        input.setY(height - STATUS - INPUT + 7);
        input.setWidth(Math.max(10, pw - PAD * 2 - 8 - 46));
        input.setTextColor(c("text"));
        boolean showReason = !collapsed && tab == Tab.REVIEW && discardOpen;
        reason.visible = showReason;
        reason.active = showReason;
        reason.setX(PAD + 4);
        reason.setY(height - STATUS - INPUT - 16 + 4);
        reason.setWidth(Math.max(10, pw - PAD * 2 - 8));
        reason.setTextColor(c("text"));
    }

    @Override
    public void removed() {
        lastTab = tab;
        lastCollapsed = collapsed;
        OrbitCamera.stop();
        if (previousHideGui != null && minecraft != null) {
            com.p2s.compat.P2SClientLifecycleCompat.setHideGui(minecraft, previousHideGui);
            previousHideGui = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void p2sRenderBackground(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        // The world stays visible; panels paint their own backgrounds.
    }

    // ------------------------------------------------------------------ render

    @Override
    public void p2sRender(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        hits.clear();
        layoutInputs();
        trackRun();
        int pw = panelWidth();
        if (collapsed) {
            renderCollapsed(gfx, mouseX, mouseY);
        } else {
            gfx.fill(0, 0, pw, height, c("panel"));
            gfx.fill(pw - 1, 0, pw, height, c("line"));
            renderTopBar(gfx, mouseX, mouseY, pw);
            renderTabs(gfx, mouseX, mouseY, pw);
            switch (tab) {
                case CHAT -> renderChat(gfx, mouseX, mouseY, pw);
                case FILES -> renderFiles(gfx, mouseX, mouseY, pw);
                case REVIEW -> renderReview(gfx, mouseX, mouseY, pw);
            }
            if (tab == Tab.CHAT) {
                renderInputBar(gfx, mouseX, mouseY, pw);
            } else if (tab == Tab.REVIEW) {
                renderReviewActions(gfx, mouseX, mouseY, pw);
            }
            renderStatusBar(gfx, pw);
        }
        renderWorldControls(gfx, mouseX, mouseY, pw);
        p2sSuperRender(gfx, mouseX, mouseY, delta);
        if (menuOpen) {
            renderMenu(gfx, mouseX, mouseY, pw);
        }
    }

    private void trackRun() {
        boolean running = ClientAgentManager.isAgentRunning();
        if (running && runStarted == 0) {
            runStarted = System.currentTimeMillis();
        } else if (!running) {
            runStarted = 0;
        }
    }

    private void renderCollapsed(P2SGfx gfx, int mouseX, int mouseY) {
        gfx.fill(0, 0, COLLAPSED, height, c("panel"));
        gfx.fill(COLLAPSED - 1, 0, COLLAPSED, height, c("line"));
        button(gfx, "expand", 2, 4, COLLAPSED - 3, 16, "»", false, mouseX, mouseY, () -> setCollapsed(false));
        int pending = pendingFiles().size();
        if (pending > 0) {
            gfx.fill(2, 22, COLLAPSED - 3, 34, c("accent"));
            String n = String.valueOf(pending);
            gfx.drawString(font, n, (COLLAPSED - font.width(n)) / 2, 24, c("on-accent"), false);
        }
        if (ClientAgentManager.isAgentRunning()) {
            gfx.fill(5, 40, COLLAPSED - 6, 44, c("tool-running"));
        }
    }

    private void renderTopBar(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        gfx.fill(0, 0, pw - 1, TOP, c("inset"));
        gfx.fill(0, TOP - 1, pw - 1, TOP, c("line"));
        int x = PAD;
        gfx.fill(x, 6, x + 6, 12, c("accent"));
        gfx.fill(x + 2, 8, x + 4, 10, c("inset"));
        x += 9;
        gfx.drawString(font, "P2S", x, 6, c("text"), false);
        x += font.width("P2S") + 4;
        String project = ClientSessionState.hasProject() ? ClientSessionState.getProjectName()
                : P2SI18n.tr("screen.p2s.wb.no_project").getString();
        String file = ClientSessionState.getSelectedWorkspaceLabel();
        String crumb = "/ " + project + (file == null || file.isBlank() ? "" : "  /  " + file);
        int right = pw - PAD - 34;
        gfx.drawString(font, ellipsize(crumb, right - x), x, 6, c("text"), false);
        button(gfx, "menu", pw - PAD - 32, 3, pw - PAD - 16, TOP - 4, "⋯", menuOpen, mouseX, mouseY,
                () -> menuOpen = !menuOpen);
        button(gfx, "collapse", pw - PAD - 14, 3, pw - PAD, TOP - 4, "«", false, mouseX, mouseY,
                () -> setCollapsed(true));
    }

    private void renderTabs(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        int y = TOP;
        gfx.fill(0, y, pw - 1, y + TABS, c("panel"));
        gfx.fill(0, y + TABS - 1, pw - 1, y + TABS, c("line"));
        int x = PAD;
        int pending = pendingFiles().size();
        for (Tab t : Tab.values()) {
            String label = switch (t) {
                case CHAT -> P2SI18n.tr("screen.p2s.wb.tab.chat").getString();
                case FILES -> P2SI18n.tr("screen.p2s.wb.tab.files").getString();
                case REVIEW -> P2SI18n.tr("screen.p2s.wb.tab.review").getString();
            };
            int w = font.width(label) + 10 + (t == Tab.REVIEW && pending > 0 ? font.width(" " + pending) + 4 : 0);
            boolean selected = tab == t;
            float h = hoverAmount("tab" + t, mouseX, mouseY, x, y, x + w, y + TABS);
            if (h > 0 && !selected) {
                gfx.fill(x, y + 1, x + w, y + TABS - 1, WbPalette.fade(c("hover"), h));
            }
            gfx.drawString(font, label, x + 5, y + 4, selected ? c("text") : c("muted"), false);
            if (t == Tab.REVIEW && pending > 0) {
                String n = String.valueOf(pending);
                int bx = x + 5 + font.width(label) + 3;
                gfx.fill(bx, y + 3, bx + font.width(n) + 4, y + 13, c("accent"));
                gfx.drawString(font, n, bx + 2, y + 4, c("on-accent"), false);
            }
            if (selected) {
                gfx.fill(x + 2, y + TABS - 2, x + w - 2, y + TABS, c("accent"));
            }
            Tab target = t;
            hits.add(new Hit(x, y, x + w, y + TABS, () -> switchTab(target)));
            x += w + 2;
        }
    }

    private void renderStatusBar(P2SGfx gfx, int pw) {
        int y = height - STATUS;
        gfx.fill(0, y, pw - 1, height, c("inset"));
        gfx.fill(0, y, pw - 1, y + 1, c("line"));
        boolean running = ClientAgentManager.isAgentRunning();
        int dot = running ? c("tool-running") : !pendingFiles().isEmpty() ? c("warning") : c("tool-done");
        gfx.fill(PAD, y + 4, PAD + 4, y + 8, dot);
        String state;
        if (running) {
            long secs = runStarted == 0 ? 0 : (System.currentTimeMillis() - runStarted) / 1000;
            state = P2SI18n.tr("screen.p2s.wb.status.running", secs).getString();
        } else if (!pendingFiles().isEmpty()) {
            state = P2SI18n.tr("screen.p2s.wb.status.review").getString();
        } else {
            state = P2SI18n.tr("screen.p2s.wb.status.idle").getString();
        }
        gfx.drawString(font, state, PAD + 8, y + 2, c("muted"), false);
        String keys = "Alt+1-3  Esc";
        gfx.drawString(font, keys, pw - PAD - font.width(keys), y + 2, c("faint"), false);
    }

    // ------------------------------------------------------------------ chat

    private void renderChat(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        int top = contentTop();
        int bottom = contentBottom();
        int maxScroll = Math.max(0, chatContentHeight - (bottom - top));
        if (stickBottom) {
            chatScroll = maxScroll;
        }
        chatScroll = Math.max(0, Math.min(chatScroll, maxScroll));
        gfx.enableScissor(0, top, pw - 1, bottom);
        int y = top + PAD - (int) chatScroll;
        int start = y;
        int w = pw - PAD * 2;

        y = renderPlan(gfx, y, w);
        List<ClientSessionState.ChatMessage> messages = ClientSessionState.getMessages();
        if (messages.isEmpty() && !ClientAgentManager.isAgentRunning() && pendingFiles().isEmpty()) {
            y = renderWelcome(gfx, y, w, mouseX, mouseY, top, bottom);
        }
        int i = 0;
        while (i < messages.size()) {
            ClientSessionState.ChatMessage message = messages.get(i);
            if (isTool(message)) {
                int j = i;
                while (j < messages.size() && isTool(messages.get(j))) {
                    j++;
                }
                y = renderToolCard(gfx, messages.subList(i, j), y, w, mouseX, mouseY, top, bottom);
                i = j;
                continue;
            }
            y = renderMessage(gfx, message, y, w);
            i++;
        }
        if (ClientSessionState.isStreaming()) {
            String text = ClientSessionState.getStreamingText();
            if (text != null && !text.isBlank()) {
                y = renderAgentText(gfx, text + (System.currentTimeMillis() / 500 % 2 == 0 ? "▌" : ""), y, w);
            }
        }
        if (ClientAgentManager.isAgentRunning() && !ClientSessionState.isStreaming()) {
            gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.thinking").getString() + dots(), PAD, y, c("faint"), false);
            y += LINE + 2;
        }
        y = renderChoice(gfx, y, w, mouseX, mouseY, top, bottom);
        y = renderPendingCard(gfx, y, w, mouseX, mouseY, top, bottom);
        chatContentHeight = y - start + PAD;
        gfx.disableScissor();
        renderScrollbar(gfx, pw - 4, top, bottom, chatScroll, chatContentHeight);
    }

    private static final String[] EXAMPLES = {
            "screen.p2s.wb.example.1", "screen.p2s.wb.example.2", "screen.p2s.wb.example.3"};

    /** Empty conversation: what the panel is for, and a few prompts that fill the input. */
    private int renderWelcome(P2SGfx gfx, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        y += 8;
        gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.welcome.title").getString(), PAD, y, c("text"), false);
        y += LINE + 4;
        for (FormattedCharSequence line : wrap(P2SI18n.tr(ClientSessionState.hasProject()
                ? "screen.p2s.wb.welcome.body" : "screen.p2s.wb.welcome.no_project").getString(), w)) {
            gfx.drawString(font, line, PAD, y, c("muted"), false);
            y += LINE;
        }
        y += 8;
        gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.welcome.try").getString(), PAD, y, c("faint"), false);
        y += LINE + 2;
        for (String key : EXAMPLES) {
            String text = P2SI18n.tr(key).getString();
            List<FormattedCharSequence> lines = wrap(text, w - 16);
            int h = lines.size() * LINE + 6;
            float hv = hoverAmount("ex-" + key, mouseX, mouseY, PAD, y, PAD + w, y + h);
            gfx.fill(PAD, y, PAD + w, y + h, WbPalette.mix(c("inset"), c("hover"), hv));
            outline(gfx, PAD, y, PAD + w, y + h, c("line"));
            int ly = y + 3;
            for (FormattedCharSequence line : lines) {
                gfx.drawString(font, line, PAD + 8, ly, c("text"), false);
                ly += LINE;
            }
            if (y >= clipTop && y + h <= clipBottom) {
                hits.add(new Hit(PAD, y, PAD + w, y + h, () -> {
                    input.setValue(text);
                    draft = text;
                    setFocused(input);
                }));
            }
            y += h + 4;
        }
        return y + 4;
    }

    private static boolean isTool(ClientSessionState.ChatMessage message) {
        return ClientSessionState.MESSAGE_KIND_TOOL_CALL.equals(message.kind())
                || ClientSessionState.MESSAGE_KIND_TOOL_ERROR.equals(message.kind());
    }

    private int renderMessage(P2SGfx gfx, ClientSessionState.ChatMessage message, int y, int w) {
        String role = message.role();
        if (P2SI18n.ROLE_USER.equals(role)) {
            List<FormattedCharSequence> lines = wrap(message.text(), w - 10);
            int h = 4 + LINE + lines.size() * LINE + 3;
            gfx.fill(PAD, y, PAD + w, y + h, c("raised"));
            gfx.fill(PAD, y, PAD + 2, y + h, c("line"));
            gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.you").getString(), PAD + 6, y + 3, c("faint"), false);
            int ly = y + 3 + LINE;
            for (FormattedCharSequence line : lines) {
                gfx.drawString(font, line, PAD + 6, ly, c("text"), false);
                ly += LINE;
            }
            return y + h + 5;
        }
        if (P2SI18n.ROLE_ASSISTANT.equals(role)) {
            return renderAgentText(gfx, message.text(), y, w);
        }
        // System notes: one muted block, no chrome.
        int ly = y;
        for (FormattedCharSequence line : wrap(message.text(), w)) {
            gfx.drawString(font, line, PAD, ly, c("faint"), false);
            ly += LINE;
        }
        return ly + 4;
    }

    private int renderAgentText(P2SGfx gfx, String text, int y, int w) {
        gfx.drawString(font, "P2S Agent", PAD, y, c("accent"), false);
        int ly = y + LINE;
        for (FormattedCharSequence line : wrap(text, w - 4)) {
            gfx.drawString(font, line, PAD + 4, ly, c("text"), false);
            ly += LINE;
        }
        return ly + 5;
    }

    private int renderToolCard(P2SGfx gfx, List<ClientSessionState.ChatMessage> tools, int y, int w,
                               int mouseX, int mouseY, int clipTop, int clipBottom) {
        int x = PAD;
        int headerH = 13;
        boolean running = ClientAgentManager.isAgentRunning();
        // Measure first so the card background sits behind the rows.
        int bodyH = 0;
        for (ClientSessionState.ChatMessage tool : tools) {
            bodyH += LINE + 1;
            if (expanded.contains(tool.id()) && tool.detail() != null && !tool.detail().isBlank()) {
                bodyH += wrap(tool.detail(), w - 22).size() * LINE + 2;
            }
        }
        int h = headerH + bodyH + 4;
        gfx.fill(x, y, x + w, y + h, c("inset"));
        outline(gfx, x, y, x + w, y + h, c("line"));
        gfx.fill(x + 1, y + 1, x + w - 1, y + headerH, c("raised"));
        long failed = tools.stream().filter(t -> ClientSessionState.MESSAGE_KIND_TOOL_ERROR.equals(t.kind())).count();
        String title = P2SI18n.tr("screen.p2s.wb.tools", tools.size()).getString()
                + (failed > 0 ? "  ·  " + P2SI18n.tr("screen.p2s.wb.tools_failed", failed).getString() : "");
        gfx.drawString(font, title, x + 5, y + 3, c("text"), false);
        if (running) {
            int bx2 = x + w - 3;
            int bx1 = bx2 - font.width(P2SI18n.tr("screen.p2s.wb.stop").getString()) - 12;
            if (y + 2 >= clipTop && y + headerH - 1 <= clipBottom) {
                button(gfx, "stop-card", bx1, y + 1, bx2, y + headerH - 1, "■ " + P2SI18n.tr("screen.p2s.wb.stop").getString(),
                        false, mouseX, mouseY, ClientAgentManager::stopCurrentRun);
            }
        }
        int ly = y + headerH + 2;
        for (ClientSessionState.ChatMessage tool : tools) {
            boolean error = ClientSessionState.MESSAGE_KIND_TOOL_ERROR.equals(tool.kind());
            gfx.drawString(font, error ? "✕" : "✓", x + 5, ly, error ? c("tool-failed") : c("tool-done"), false);
            boolean hasDetail = tool.detail() != null && !tool.detail().isBlank();
            String summary = (hasDetail ? (expanded.contains(tool.id()) ? "▾ " : "▸ ") : "") + tool.text();
            gfx.drawString(font, ellipsize(summary, w - 22), x + 16, ly, error ? c("danger") : c("muted"), false);
            if (hasDetail && ly >= clipTop && ly + LINE <= clipBottom) {
                String id = tool.id();
                hits.add(new Hit(x, ly - 1, x + w, ly + LINE, () -> toggle(id)));
            }
            ly += LINE + 1;
            if (expanded.contains(tool.id()) && hasDetail) {
                for (FormattedCharSequence line : wrap(tool.detail(), w - 22)) {
                    gfx.drawString(font, line, x + 16, ly, c("faint"), false);
                    ly += LINE;
                }
                ly += 2;
            }
        }
        return y + h + 5;
    }

    private int renderPlan(P2SGfx gfx, int y, int w) {
        List<ClientSessionState.PlanItem> items = ClientSessionState.getPlanItems();
        if (items.isEmpty()) {
            return y;
        }
        long done = items.stream().filter(item -> "completed".equals(item.status())).count();
        int h = 13 + items.size() * LINE + 4;
        gfx.fill(PAD, y, PAD + w, y + h, c("inset"));
        outline(gfx, PAD, y, PAD + w, y + h, c("line"));
        gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.plan", done, items.size()).getString(), PAD + 5, y + 3, c("text"), false);
        int barX = PAD + w - 44;
        for (int k = 0; k < items.size() && k < 8; k++) {
            int color = "completed".equals(items.get(k).status()) ? c("accent") : c("line");
            gfx.fill(barX + k * 5, y + 5, barX + k * 5 + 4, y + 7, color);
        }
        int ly = y + 14;
        for (ClientSessionState.PlanItem item : items) {
            String status = item.status();
            String mark = "completed".equals(status) ? "✓" : "in_progress".equals(status) ? "▶" : "○";
            int color = "completed".equals(status) ? c("tool-done") : "in_progress".equals(status) ? c("tool-running") : c("tool-waiting");
            gfx.drawString(font, mark, PAD + 5, ly, color, false);
            gfx.drawString(font, ellipsize(item.step(), w - 20), PAD + 16, ly,
                    "in_progress".equals(status) ? c("text") : c("muted"), false);
            ly += LINE;
        }
        return y + h + 6;
    }

    private int renderChoice(P2SGfx gfx, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null) {
            return y;
        }
        List<FormattedCharSequence> prompt = wrap(choice.prompt(), w - 10);
        int h = 4 + prompt.size() * LINE + 2 + choice.options().size() * 15 + 3;
        gfx.fill(PAD, y, PAD + w, y + h, c("inset"));
        outline(gfx, PAD, y, PAD + w, y + h, c("accent"));
        int ly = y + 4;
        for (FormattedCharSequence line : prompt) {
            gfx.drawString(font, line, PAD + 5, ly, c("text"), false);
            ly += LINE;
        }
        ly += 2;
        for (ClientSessionState.ChoiceOption option : choice.options()) {
            String id = option.id();
            if (ly >= clipTop && ly + 13 <= clipBottom) {
                button(gfx, "choice-" + id, PAD + 4, ly, PAD + w - 4, ly + 13, ellipsize(option.label(), w - 16),
                        false, mouseX, mouseY, () -> ClientAgentManager.submitChoiceSelection(id));
            }
            ly += 15;
        }
        return y + h + 6;
    }

    private int renderPendingCard(P2SGfx gfx, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        List<ClientSessionState.WorkspaceFileInfo> pending = pendingFiles();
        if (pending.isEmpty()) {
            return y;
        }
        int rowH = 12;
        int h = 14 + pending.size() * rowH + 18;
        gfx.fill(PAD, y, PAD + w, y + h, c("inset"));
        outline(gfx, PAD, y, PAD + w, y + h, c("line"));
        gfx.fill(PAD + 1, y + 1, PAD + w - 1, y + 13, WbPalette.fade(c("accent"), 0.18f));
        gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.pending", pending.size()).getString(), PAD + 5, y + 3, c("accent"), false);
        int ly = y + 15;
        for (ClientSessionState.WorkspaceFileInfo file : pending) {
            String path = file.path();
            String blocks = blockCounts(file);
            float h2 = hoverAmount("pend-" + path, mouseX, mouseY, PAD + 1, ly - 1, PAD + w - 1, ly + rowH - 1);
            if (h2 > 0) {
                gfx.fill(PAD + 1, ly - 1, PAD + w - 1, ly + rowH - 1, WbPalette.fade(c("hover"), h2));
            }
            gfx.drawString(font, ellipsize(leaf(path), w - 20 - font.width(blocks)), PAD + 6, ly + 1, c("text"), false);
            gfx.drawString(font, blocks, PAD + w - 6 - font.width(blocks), ly + 1, c("accent"), false);
            if (ly >= clipTop && ly + rowH <= clipBottom) {
                hits.add(new Hit(PAD + 1, ly - 1, PAD + w - 1, ly + rowH - 1, () -> openReview(path)));
            }
            ly += rowH;
        }
        String start = P2SI18n.tr("screen.p2s.wb.start_review").getString() + " →";
        int bx2 = PAD + w - 4;
        int bx1 = bx2 - font.width(start) - 10;
        if (ly + 2 >= clipTop && ly + 15 <= clipBottom) {
            primaryButton(gfx, "start-review", bx1, ly + 2, bx2, ly + 15, start, mouseX, mouseY,
                    () -> openReview(pending.getFirst().path()));
        }
        return y + h + 6;
    }

    private void renderInputBar(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        int y = height - STATUS - INPUT;
        gfx.fill(0, y, pw - 1, y + INPUT, c("panel"));
        int x1 = PAD;
        int x2 = pw - PAD - 44;
        gfx.fill(x1, y + 3, x2, y + INPUT - 3, c("input-bg"));
        outline(gfx, x1, y + 3, x2, y + INPUT - 3, input.isFocused() ? c("focus") : c("input-border"));
        gfx.drawString(font, "›", x1 + 3, y + 7, c("accent"), false);
        if (input.getValue().isEmpty() && !input.isFocused()) {
            String hint = P2SI18n.tr(ClientAgentManager.isAgentRunning() ? "screen.p2s.wb.input.queue"
                    : "screen.p2s.wb.input.hint").getString();
            gfx.drawString(font, ellipsize(hint, x2 - x1 - 14), x1 + 10, y + 7, c("faint"), false);
        }
        boolean running = ClientAgentManager.isAgentRunning();
        String label = P2SI18n.tr(running ? "screen.p2s.wb.queue" : "screen.p2s.wb.send").getString();
        primaryButton(gfx, "send", pw - PAD - 40, y + 3, pw - PAD, y + INPUT - 3, label, mouseX, mouseY, this::send);
    }

    private void send() {
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        ClientAgentManager.submitUserMessage(text);
        input.setValue("");
        draft = "";
        stickBottom = true;
    }

    // ------------------------------------------------------------------ review

    private void renderReview(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        int top = contentTop();
        int bottom = contentBottom() - (discardOpen ? 16 : 0);
        List<ClientSessionState.WorkspaceFileInfo> pending = pendingFiles();
        String selected = ClientSessionState.getSelectedWorkspacePath();
        boolean selectedPending = pending.stream().anyMatch(f -> f.path().equals(selected));
        if (pending.isEmpty() && appliedPath.isEmpty()) {
            centered(gfx, P2SI18n.tr("screen.p2s.wb.review.empty").getString(), pw, (top + bottom) / 2, c("faint"));
            return;
        }
        int maxScroll = Math.max(0, reviewContentHeight - (bottom - top));
        reviewScroll = Math.max(0, Math.min(reviewScroll, maxScroll));
        gfx.enableScissor(0, top, pw - 1, bottom);
        int y = top + 4 - (int) reviewScroll;
        int start = y;
        int w = pw - PAD * 2;
        // File strip.
        for (int k = 0; k < pending.size(); k++) {
            ClientSessionState.WorkspaceFileInfo file = pending.get(k);
            boolean isSel = file.path().equals(selected);
            String path = file.path();
            float h = hoverAmount("rf-" + path, mouseX, mouseY, PAD, y, PAD + w, y + 12);
            gfx.fill(PAD, y, PAD + w, y + 12, isSel ? c("selected") : WbPalette.fade(c("hover"), h));
            if (isSel) {
                gfx.fill(PAD, y, PAD + 2, y + 12, c("accent"));
            }
            String counts = blockCounts(file);
            gfx.drawString(font, (k + 1) + ". " + ellipsize(leaf(path), w - 30 - font.width(counts)), PAD + 5, y + 2,
                    isSel ? c("text") : c("muted"), false);
            gfx.drawString(font, counts, PAD + w - 4 - font.width(counts), y + 2, c("accent"), false);
            if (y >= top && y + 12 <= bottom) {
                hits.add(new Hit(PAD, y, PAD + w, y + 12, () -> selectFile(path)));
            }
            y += 13;
        }
        y += 4;
        gfx.fill(PAD, y, PAD + w, y + 1, c("line"));
        y += 6;
        if (!selectedPending) {
            String note = appliedPath.equals(selected) && !selected.isEmpty()
                    ? P2SI18n.tr("screen.p2s.wb.review.applied", leaf(selected)).getString()
                    : P2SI18n.tr("screen.p2s.wb.review.pick").getString();
            for (FormattedCharSequence line : wrap(note, w)) {
                gfx.drawString(font, line, PAD, y, c("success"), false);
                y += LINE;
            }
        } else {
            y = renderSummary(gfx, selected, y, w);
            y = renderDiffToggle(gfx, selected, y, w, mouseX, mouseY, top, bottom);
        }
        reviewContentHeight = y - start + PAD;
        gfx.disableScissor();
        renderScrollbar(gfx, pw - 4, top, bottom, reviewScroll, reviewContentHeight);
    }

    private int renderSummary(P2SGfx gfx, String path, int y, int w) {
        gfx.drawString(font, ellipsize(path, w), PAD, y, c("text"), false);
        y += LINE + 2;
        String risk = ClientSessionState.getPendingRisk();
        String summary = ClientSessionState.getPreviewSummary();
        if (path.equals(ClientSessionState.getPendingPath()) && summary != null && !summary.isBlank()) {
            gfx.fill(PAD, y - 1, PAD + 2, y + LINE * Math.min(3, wrap(summary, w - 8).size()), c("accent"));
            for (FormattedCharSequence line : wrap(summary, w - 8)) {
                gfx.drawString(font, line, PAD + 6, y, c("text"), false);
                y += LINE;
            }
            y += 3;
        }
        ClientPreviewStore.FilePreview preview = ClientPreviewStore.file(path);
        if (preview != null) {
            JsonObject s = preview.summary();
            int placed = s.has("placed") ? s.get("placed").getAsInt() : 0;
            int removed = s.has("removed") ? s.get("removed").getAsInt() : 0;
            gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.counts", placed, removed).getString(), PAD, y, c("muted"), false);
            y += LINE;
            if (preview.bounds() != null) {
                var b = preview.bounds();
                String size = (int) b.getXsize() + " × " + (int) b.getYsize() + " × " + (int) b.getZsize();
                gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.size", size).getString(), PAD, y, c("muted"), false);
                y += LINE;
            }
            if (s.has("materials") && !s.getAsJsonArray("materials").isEmpty()) {
                gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.materials").getString(), PAD, y + 4, c("muted"), false);
                int mx = PAD + font.width(P2SI18n.tr("screen.p2s.wb.review.materials").getString()) + 4;
                for (var element : s.getAsJsonArray("materials")) {
                    JsonObject material = element.getAsJsonObject();
                    ItemStack icon = iconFor(material.get("block").getAsString());
                    if (mx + 18 > PAD + w) {
                        break;
                    }
                    gfx.renderItem(icon, mx, y);
                    String count = "×" + material.get("count").getAsInt();
                    gfx.drawString(font, count, mx + 16, y + 8, c("faint"), false);
                    mx += 18 + font.width(count) + 2;
                }
                y += 18;
            }
        } else {
            gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.loading").getString(), PAD, y, c("faint"), false);
            y += LINE;
        }
        if (risk != null && !risk.isBlank() && path.equals(ClientSessionState.getPendingPath())) {
            int color = "high".equals(risk) ? c("danger") : "medium".equals(risk) ? c("warning") : c("success");
            gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.risk", risk).getString(), PAD, y, color, false);
            y += LINE;
        }
        String detail = ClientSessionState.getPreviewDetail();
        if (path.equals(ClientSessionState.getPendingPath()) && detail != null && !detail.isBlank()) {
            for (FormattedCharSequence line : wrap(detail, w)) {
                gfx.drawString(font, line, PAD, y, c("warning"), false);
                y += LINE;
            }
        }
        return y + 4;
    }

    private int renderDiffToggle(P2SGfx gfx, String path, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        String label = (diffOpen ? "▾ " : "▸ ") + P2SI18n.tr("screen.p2s.wb.review.diff").getString();
        if (y >= clipTop && y + 12 <= clipBottom) {
            button(gfx, "diff", PAD, y, PAD + font.width(label) + 10, y + 12, label, diffOpen, mouseX, mouseY,
                    () -> diffOpen = !diffOpen);
        }
        y += 16;
        if (!diffOpen) {
            return y;
        }
        String committed = text(path, true);
        String staged = text(path, false);
        if (committed == null || staged == null) {
            gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.loading").getString(), PAD, y, c("faint"), false);
            return y + LINE;
        }
        List<LineDiff.Row> rows = LineDiff.diff(committed, staged, DIFF_CONTEXT);
        int numW = font.width("0000");
        for (LineDiff.Row row : rows) {
            int bg;
            int fg;
            String sign;
            switch (row.kind()) {
                case ADD -> { bg = c("diff-add-bg"); fg = c("diff-add-text"); sign = "+"; }
                case REMOVE -> { bg = c("diff-remove-bg"); fg = c("diff-remove-text"); sign = "-"; }
                case FOLD -> { bg = c("inset"); fg = c("faint"); sign = ""; }
                default -> { bg = 0; fg = c("muted"); sign = " "; }
            }
            if (bg != 0) {
                gfx.fill(PAD, y - 1, PAD + w, y + LINE - 1, bg);
            }
            if (row.kind() == LineDiff.Kind.FOLD) {
                gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.folded", row.folded()).getString(), PAD + 4, y, fg, false);
            } else {
                int number = row.newLine() > 0 ? row.newLine() : row.oldLine();
                String n = String.valueOf(number);
                gfx.drawString(font, n, PAD + numW - font.width(n), y, c("faint"), false);
                gfx.drawString(font, sign + " " + ellipsize(row.text(), w - numW - 14), PAD + numW + 4, y, fg, false);
            }
            y += LINE;
        }
        return y + 4;
    }

    private void renderReviewActions(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        int y = height - STATUS - INPUT;
        int barTop = y - (discardOpen ? 16 : 0);
        gfx.fill(0, barTop, pw - 1, y + INPUT, c("panel"));
        gfx.fill(0, barTop, pw - 1, barTop + 1, c("line"));
        String selected = ClientSessionState.getSelectedWorkspacePath();
        List<ClientSessionState.WorkspaceFileInfo> pending = pendingFiles();
        boolean selectedPending = pending.stream().anyMatch(f -> f.path().equals(selected));
        if (discardOpen) {
            int ry = y - 15;
            gfx.fill(PAD, ry, pw - PAD, ry + 13, c("input-bg"));
            outline(gfx, PAD, ry, pw - PAD, ry + 13, reason.isFocused() ? c("focus") : c("input-border"));
            if (reason.getValue().isEmpty() && !reason.isFocused()) {
                gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.discard_reason").getString(), PAD + 4, ry + 3, c("faint"), false);
            }
        }
        int x = PAD;
        if (selectedPending && !discardOpen) {
            String apply = "✓ " + P2SI18n.tr("screen.p2s.wb.apply").getString();
            int aw = font.width(apply) + 12;
            primaryButton(gfx, "apply", x, y + 4, x + aw, y + INPUT - 4, apply, mouseX, mouseY, this::applySelected);
            x += aw + 4;
            String discard = "✕ " + P2SI18n.tr("screen.p2s.wb.discard").getString();
            int dw = font.width(discard) + 12;
            dangerButton(gfx, "discard", x, y + 4, x + dw, y + INPUT - 4, discard, mouseX, mouseY, () -> {
                discardOpen = true;
                reason.setValue("");
                setFocused(reason);
            });
        } else if (discardOpen) {
            String confirm = P2SI18n.tr("screen.p2s.wb.discard_confirm", leaf(selected)).getString();
            int cw = font.width(confirm) + 12;
            dangerButton(gfx, "discard-confirm", x, y + 4, x + cw, y + INPUT - 4, confirm, mouseX, mouseY, this::discardSelected);
            x += cw + 4;
            String cancel = P2SI18n.tr("screen.p2s.wb.cancel").getString();
            button(gfx, "discard-cancel", x, y + 4, x + font.width(cancel) + 12, y + INPUT - 4, cancel, false, mouseX, mouseY,
                    () -> discardOpen = false);
        } else if (!pending.isEmpty()) {
            String next = P2SI18n.tr("screen.p2s.wb.next").getString() + " →";
            String target = pending.getFirst().path();
            primaryButton(gfx, "next", x, y + 4, x + font.width(next) + 12, y + INPUT - 4, next, mouseX, mouseY,
                    () -> selectFile(target));
        }
        if (!discardOpen && pending.size() > 1) {
            String all = P2SI18n.tr("screen.p2s.wb.apply_all", pending.size()).getString();
            int w = font.width(all) + 12;
            button(gfx, "apply-all", pw - PAD - w, y + 4, pw - PAD, y + INPUT - 4, all, false, mouseX, mouseY, () -> {
                appliedPath = "";
                ClientAgentManager.submitPatchApplyAll();
            });
        }
    }

    private void applySelected() {
        // Stay on the applied file so the player can look at the result before moving on.
        appliedPath = ClientSessionState.getSelectedWorkspacePath();
        ClientAgentManager.submitPatchApply();
    }

    private void discardSelected() {
        String why = reason.getValue().trim();
        discardOpen = false;
        appliedPath = "";
        ClientAgentManager.submitPatchDiscard(why);
    }

    // ------------------------------------------------------------------ files

    private void renderFiles(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        int top = contentTop();
        int bottom = contentBottom();
        List<ClientSessionState.WorkspaceFileInfo> files = ClientSessionState.getWorkspaceFiles();
        if (files.isEmpty()) {
            centered(gfx, P2SI18n.tr("screen.p2s.wb.files.empty").getString(), pw, (top + bottom) / 2, c("faint"));
            return;
        }
        if (filePath.isEmpty() || files.stream().noneMatch(f -> f.path().equals(filePath))) {
            String selected = ClientSessionState.getSelectedWorkspacePath();
            filePath = selected != null && !selected.isBlank() ? selected : files.getFirst().path();
        }
        int listW = Math.min(96, (pw - PAD * 2) / 3);
        int lx = PAD;
        int y = top + 4;
        gfx.fill(lx, top + 2, lx + listW, bottom - 2, c("inset"));
        outline(gfx, lx, top + 2, lx + listW, bottom - 2, c("line"));
        for (ClientSessionState.WorkspaceFileInfo file : files) {
            if (y + 11 > bottom - 2) {
                break;
            }
            boolean isSel = file.path().equals(filePath);
            String path = file.path();
            float h = hoverAmount("fl-" + path, mouseX, mouseY, lx + 1, y, lx + listW - 1, y + 11);
            gfx.fill(lx + 1, y, lx + listW - 1, y + 11, isSel ? c("selected") : WbPalette.fade(c("hover"), h));
            gfx.drawString(font, ellipsize(leaf(path), listW - 12), lx + 4, y + 2, isSel ? c("text") : c("muted"), false);
            if (file.hasPendingPatch()) {
                gfx.fill(lx + listW - 6, y + 4, lx + listW - 3, y + 7, c("accent"));
            }
            hits.add(new Hit(lx + 1, y, lx + listW - 1, y + 11, () -> {
                filePath = path;
                fileScroll = 0;
            }));
            y += 12;
        }
        int vx = lx + listW + 4;
        int vw = pw - PAD - vx;
        boolean pending = files.stream().anyMatch(f -> f.path().equals(filePath) && f.hasPendingPatch());
        gfx.fill(vx, top + 2, vx + vw, top + 14, c("raised"));
        gfx.drawString(font, ellipsize(leaf(filePath), vw - 60), vx + 4, top + 4, c("text"), false);
        String mode = P2SI18n.tr(pending ? "screen.p2s.wb.files.proposal" : "screen.p2s.wb.files.readonly").getString();
        gfx.drawString(font, mode, vx + vw - 4 - font.width(mode), top + 4, pending ? c("accent") : c("faint"), false);
        int textTop = top + 16;
        int textBottom = bottom - 20;
        String text = text(filePath, false);
        gfx.fill(vx, textTop, vx + vw, textBottom, c("inset"));
        gfx.enableScissor(vx, textTop, vx + vw, textBottom);
        if (text == null) {
            gfx.drawString(font, P2SI18n.tr("screen.p2s.wb.review.loading").getString(), vx + 4, textTop + 3, c("faint"), false);
        } else {
            String[] lines = text.split("\n", -1);
            fileContentHeight = lines.length * LINE + 6;
            int maxScroll = Math.max(0, fileContentHeight - (textBottom - textTop));
            fileScroll = Math.max(0, Math.min(fileScroll, maxScroll));
            int ty = textTop + 3 - (int) fileScroll;
            int numW = font.width(String.valueOf(lines.length));
            for (int k = 0; k < lines.length; k++) {
                if (ty > textTop - LINE && ty < textBottom) {
                    String n = String.valueOf(k + 1);
                    gfx.drawString(font, n, vx + 3 + numW - font.width(n), ty, c("faint"), false);
                    gfx.drawString(font, ellipsize(lines[k], vw - numW - 12), vx + numW + 8, ty, tomlColor(lines[k]), false);
                }
                ty += LINE;
            }
        }
        gfx.disableScissor();
        renderScrollbar(gfx, vx + vw - 3, textTop, textBottom, fileScroll, fileContentHeight);
        String edit = "✎ " + P2SI18n.tr("screen.p2s.wb.files.edit").getString();
        button(gfx, "edit", vx, bottom - 17, vx + font.width(edit) + 12, bottom - 3, edit, false, mouseX, mouseY, () -> {
            ClientSessionState.setSelectedWorkspacePath(filePath);
            JsonObject payload = new JsonObject();
            payload.addProperty("path", filePath);
            ClientServerBridge.sendSessionAction("session_select_workspace", payload.toString());
            com.p2s.compat.P2SClientLifecycleCompat.setScreen(minecraft, new P2SChatScreen());
        });
    }

    private int tomlColor(String line) {
        String t = line.trim();
        if (t.startsWith("#")) {
            return c("faint");
        }
        if (t.startsWith("[")) {
            return c("accent");
        }
        return c("text");
    }

    // ------------------------------------------------------------------ world and menu

    private void renderWorldControls(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        if (width - pw < 120) {
            return;
        }
        boolean hasPreview = !ClientPreviewStore.files().isEmpty();
        int y2 = height - 6;
        int x2 = width - 6;
        List<String[]> chips = new ArrayList<>();
        if (OrbitCamera.active()) {
            chips.add(new String[]{"reset", P2SI18n.tr("screen.p2s.wb.world.reset").getString()});
        }
        if (hasPreview) {
            chips.add(new String[]{"ghost", P2SI18n.tr(ClientPreviewStore.hidden() ? "screen.p2s.wb.world.show"
                    : "screen.p2s.wb.world.applied_only").getString()});
        }
        for (String[] chip : chips) {
            int w = font.width(chip[1]) + 10;
            int x1 = x2 - w;
            String id = chip[0];
            float h = hoverAmount("w-" + id, mouseX, mouseY, x1, y2 - 13, x2, y2);
            gfx.fill(x1, y2 - 13, x2, y2, WbPalette.mix(c("overlay"), c("raised"), h));
            gfx.drawString(font, chip[1], x1 + 5, y2 - 10, c("on-overlay"), false);
            hits.add(new Hit(x1, y2 - 13, x2, y2, "reset".equals(id) ? OrbitCamera::stop
                    : () -> ClientPreviewStore.setHidden(!ClientPreviewStore.hidden())));
            x2 = x1 - 4;
        }
        String hint = P2SI18n.tr("screen.p2s.wb.world.hint").getString();
        if (mouseX > pw && !overPanel(mouseX, mouseY)) {
            int w = font.width(hint) + 10;
            gfx.fill(width - 6 - w, 6, width - 6, 19, c("overlay"));
            gfx.drawString(font, hint, width - 1 - w, 9, c("on-overlay"), false);
        }
    }

    private void renderMenu(P2SGfx gfx, int mouseX, int mouseY, int pw) {
        List<Object[]> items = new ArrayList<>();
        items.add(new Object[]{"screen.p2s.wb.menu.new_session", (Runnable) ClientAgentManager::newSession});
        items.add(new Object[]{"screen.p2s.wb.menu.sessions", (Runnable) () -> com.p2s.compat.P2SClientLifecycleCompat.setScreen(minecraft, new P2SSessionListScreen(this))});
        items.add(new Object[]{"screen.p2s.wb.menu.projects", (Runnable) () -> com.p2s.compat.P2SClientLifecycleCompat.setScreen(minecraft, new P2SProjectListScreen(this))});
        items.add(new Object[]{"screen.p2s.wb.menu.checkpoints", (Runnable) () -> com.p2s.compat.P2SClientLifecycleCompat.setScreen(minecraft, new P2SCheckpointListScreen(this))});
        items.add(new Object[]{"screen.p2s.wb.menu.undo", (Runnable) () -> ClientServerBridge.sendSessionAction("undo", "")});
        items.add(new Object[]{"screen.p2s.wb.menu.redo", (Runnable) () -> ClientServerBridge.sendSessionAction("redo", "")});
        if (ClientAgentManager.canManualCompact()) {
            items.add(new Object[]{"screen.p2s.wb.menu.compact", (Runnable) ClientAgentManager::submitManualCompact});
        }
        items.add(new Object[]{"screen.p2s.wb.menu.export", (Runnable) ClientDiagnostics::start});
        items.add(new Object[]{"screen.p2s.wb.menu.settings", (Runnable) () -> com.p2s.compat.P2SClientLifecycleCompat.setScreen(minecraft, new P2SConfigScreen(this))});
        items.add(new Object[]{"screen.p2s.wb.menu.classic", (Runnable) () -> com.p2s.compat.P2SClientLifecycleCompat.setScreen(minecraft, new P2SChatScreen())});
        int w = 0;
        for (Object[] item : items) {
            w = Math.max(w, font.width(P2SI18n.tr((String) item[0]).getString()));
        }
        w += 16;
        int x2 = pw - PAD - 16;
        int x1 = Math.max(2, x2 - w);
        int y1 = TOP - 2;
        int h = items.size() * 13 + 4;
        gfx.pushPopupLayer();
        gfx.fill(x1, y1, x2, y1 + h, c("raised"));
        outline(gfx, x1, y1, x2, y1 + h, c("line"));
        int y = y1 + 2;
        for (Object[] item : items) {
            String key = (String) item[0];
            Runnable action = (Runnable) item[1];
            float hv = hoverAmount("m-" + key, mouseX, mouseY, x1 + 1, y, x2 - 1, y + 13);
            if (hv > 0) {
                gfx.fill(x1 + 1, y, x2 - 1, y + 13, WbPalette.fade(c("hover"), hv));
            }
            gfx.drawString(font, P2SI18n.tr(key).getString(), x1 + 8, y + 3, c("text"), false);
            hits.add(new Hit(x1 + 1, y, x2 - 1, y + 13, () -> {
                menuOpen = false;
                action.run();
            }));
            y += 13;
        }
        gfx.popPopupLayer();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean p2sMouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Topmost (last drawn) region wins; the menu is drawn last.
            for (int k = hits.size() - 1; k >= 0; k--) {
                Hit hit = hits.get(k);
                if (hit.contains(mouseX, mouseY)) {
                    boolean wasMenu = menuOpen;
                    hit.action().run();
                    if (wasMenu && menuOpen) {
                        menuOpen = false;
                    }
                    return true;
                }
            }
            if (menuOpen) {
                menuOpen = false;
                return true;
            }
        }
        if (!overPanel(mouseX, mouseY) || collapsed && mouseX >= COLLAPSED) {
            if (button == 1) {
                OrbitCamera.beginDrag();
                return true;
            }
            if (button == 2) {
                OrbitCamera.stop();
                return true;
            }
            setFocused(null);
            return true;
        }
        return p2sSuperMouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean p2sMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 1 && OrbitCamera.dragging()) {
            OrbitCamera.drag(dragX, dragY);
            return true;
        }
        return p2sSuperMouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean p2sMouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1 && OrbitCamera.dragging()) {
            OrbitCamera.endDrag();
            return true;
        }
        return p2sSuperMouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean p2sMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!overPanel(mouseX, mouseY) && !collapsed || collapsed && mouseX >= COLLAPSED) {
            if (OrbitCamera.active()) {
                OrbitCamera.zoom(vertical);
            }
            return true;
        }
        double step = vertical * LINE * 3;
        switch (tab) {
            case CHAT -> {
                chatScroll -= step;
                int view = contentBottom() - contentTop();
                stickBottom = chatScroll >= chatContentHeight - view - 1;
            }
            case REVIEW -> reviewScroll -= step;
            case FILES -> fileScroll -= step;
        }
        return true;
    }

    @Override
    public boolean p2sKeyPressed(int key, int scan, int modifiers) {
        if (key == P2SInputCompat.KEY_ESCAPE) {
            return escape();
        }
        boolean alt = (modifiers & P2SInputCompat.MOD_ALT) != 0;
        if (alt && key >= P2SInputCompat.KEY_1 && key <= P2SInputCompat.KEY_3) {
            switchTab(Tab.values()[key - P2SInputCompat.KEY_1]);
            return true;
        }
        if ((key == P2SInputCompat.KEY_ENTER || key == P2SInputCompat.KEY_KP_ENTER)) {
            if (input.isFocused() && input.visible) {
                send();
                return true;
            }
            if (reason.isFocused() && reason.visible) {
                discardSelected();
                return true;
            }
        }
        if (input.isFocused() || reason.isFocused()) {
            return p2sSuperKeyPressed(key, scan, modifiers);
        }
        if (minecraft != null && P2SInputCompat.matchesKey(ModKeyBindings.OPEN_CHAT, key, scan)) {
            onClose();
            return true;
        }
        return p2sSuperKeyPressed(key, scan, modifiers);
    }

    /** One layer per press: menu → discard box → text focus → back to chat → close. */
    private boolean escape() {
        if (menuOpen) {
            menuOpen = false;
        } else if (discardOpen) {
            discardOpen = false;
        } else if (getFocused() instanceof EditBox) {
            setFocused(null);
        } else if (tab != Tab.CHAT) {
            switchTab(Tab.CHAT);
        } else {
            onClose();
        }
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // ------------------------------------------------------------------ actions

    private void switchTab(Tab next) {
        tab = next;
        menuOpen = false;
        discardOpen = false;
        if (next == Tab.REVIEW) {
            List<ClientSessionState.WorkspaceFileInfo> pending = pendingFiles();
            String selected = ClientSessionState.getSelectedWorkspacePath();
            boolean selectedPending = pending.stream().anyMatch(f -> f.path().equals(selected));
            if (!selectedPending && !pending.isEmpty() && !appliedPath.equals(selected)) {
                selectFile(pending.getFirst().path());
            }
        }
        layoutInputs();
        if (next == Tab.CHAT) {
            setFocused(input);
        } else {
            setFocused(null);
        }
    }

    private void openReview(String path) {
        selectFile(path);
        switchTab(Tab.REVIEW);
    }

    private void selectFile(String path) {
        appliedPath = "";
        diffOpen = false;
        reviewScroll = 0;
        if (ClientSessionState.setSelectedWorkspacePath(path)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("path", path);
            ClientServerBridge.sendSessionAction("session_select_workspace", payload.toString());
        }
    }

    private void setCollapsed(boolean value) {
        collapsed = value;
        menuOpen = false;
        layoutInputs();
    }

    private void toggle(String id) {
        if (!expanded.remove(id)) {
            expanded.add(id);
        }
    }

    // ------------------------------------------------------------------ data

    private List<ClientSessionState.WorkspaceFileInfo> pendingFiles() {
        List<ClientSessionState.WorkspaceFileInfo> out = new ArrayList<>();
        for (ClientSessionState.WorkspaceFileInfo file : ClientSessionState.getWorkspaceFiles()) {
            if (file.hasPendingPatch()) {
                out.add(file);
            }
        }
        return out;
    }

    private String blockCounts(ClientSessionState.WorkspaceFileInfo file) {
        ClientPreviewStore.FilePreview preview = ClientPreviewStore.file(file.path());
        if (preview != null) {
            JsonObject s = preview.summary();
            int placed = s.has("placed") ? s.get("placed").getAsInt() : 0;
            int removed = s.has("removed") ? s.get("removed").getAsInt() : 0;
            return removed > 0 ? "+" + placed + " −" + removed : "+" + placed;
        }
        return "~" + file.pendingChangedBlocks();
    }

    /** Workspace text (committed or staged), fetched once per revision through the tool bridge. */
    private String text(String path, boolean committed) {
        String revision = "";
        for (ClientSessionState.WorkspaceFileInfo file : ClientSessionState.getWorkspaceFiles()) {
            if (file.path().equals(path)) {
                revision = file.revision() + "/" + file.pendingRevision();
            }
        }
        String key = path + "|" + committed + "|" + revision;
        String cached = texts.get(key);
        if (cached != null || loading.contains(key)) {
            return cached;
        }
        loading.add(key);
        JsonObject args = new JsonObject();
        args.addProperty("path", path);
        args.addProperty("committed", committed);
        CompletableFuture<JsonObject> reply = ClientToolBridge.call("workspace_text", args, () -> true);
        reply.whenComplete((payload, error) -> minecraft.execute(() -> {
            loading.remove(key);
            if (error == null && payload != null && payload.has("workspace_toml")) {
                texts.put(key, payload.get("workspace_toml").getAsString());
            } else {
                texts.put(key, "");
            }
        }));
        return null;
    }

    private static ItemStack iconFor(String blockId) {
        return P2SGameCompat.iconForBlock(blockId);
    }

    // ------------------------------------------------------------------ drawing helpers

    private List<FormattedCharSequence> wrap(String text, int width) {
        return font.split(FormattedText.of(text == null ? "" : text), Math.max(10, width));
    }

    private String ellipsize(String text, int width) {
        if (text == null) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        String dots = "…";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(dots))) + dots;
    }

    private static String leaf(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String dots() {
        return ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
    }

    private void centered(P2SGfx gfx, String text, int pw, int y, int color) {
        gfx.drawString(font, text, (pw - font.width(text)) / 2, y, color, false);
    }

    private static void outline(P2SGfx gfx, int x1, int y1, int x2, int y2, int color) {
        gfx.fill(x1, y1, x2, y1 + 1, color);
        gfx.fill(x1, y2 - 1, x2, y2, color);
        gfx.fill(x1, y1, x1 + 1, y2, color);
        gfx.fill(x2 - 1, y1, x2, y2, color);
    }

    private void renderScrollbar(P2SGfx gfx, int x, int top, int bottom, double scroll, int content) {
        int view = bottom - top;
        if (content <= view) {
            return;
        }
        int thumb = Math.max(12, view * view / content);
        int y = top + (int) ((view - thumb) * (scroll / Math.max(1, content - view)));
        gfx.fill(x, y, x + 2, y + thumb, c("scrollbar"));
    }

    /** Hover progress 0..1 that eases in and out over about 100 ms. */
    private float hoverAmount(String id, int mouseX, int mouseY, int x1, int y1, int x2, int y2) {
        boolean over = !menuOpen || id.startsWith("m-") ? mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2 : false;
        long now = System.nanoTime();
        long last = hoverStamp.getOrDefault(id, now);
        float step = (now - last) / (float) HOVER_NANOS;
        float value = hover.getOrDefault(id, 0f);
        value = over ? Math.min(1f, value + step) : Math.max(0f, value - step);
        hover.put(id, value);
        hoverStamp.put(id, now);
        return value;
    }

    private void button(P2SGfx gfx, String id, int x1, int y1, int x2, int y2, String label, boolean active,
                        int mouseX, int mouseY, Runnable action) {
        float h = hoverAmount(id, mouseX, mouseY, x1, y1, x2, y2);
        int bg = active ? c("selected") : WbPalette.mix(c("raised"), c("hover"), h);
        gfx.fill(x1, y1, x2, y2, bg);
        outline(gfx, x1, y1, x2, y2, c("line"));
        gfx.drawString(font, label, x1 + (x2 - x1 - font.width(label)) / 2, y1 + (y2 - y1 - 8) / 2, c("text"), false);
        hits.add(new Hit(x1, y1, x2, y2, action));
    }

    private void primaryButton(P2SGfx gfx, String id, int x1, int y1, int x2, int y2, String label,
                               int mouseX, int mouseY, Runnable action) {
        float h = hoverAmount(id, mouseX, mouseY, x1, y1, x2, y2);
        gfx.fill(x1, y1, x2, y2, WbPalette.mix(c("accent"), c("pressed"), h * 0.35f));
        gfx.drawString(font, label, x1 + (x2 - x1 - font.width(label)) / 2, y1 + (y2 - y1 - 8) / 2, c("on-accent"), false);
        hits.add(new Hit(x1, y1, x2, y2, action));
    }

    private void dangerButton(P2SGfx gfx, String id, int x1, int y1, int x2, int y2, String label,
                              int mouseX, int mouseY, Runnable action) {
        float h = hoverAmount(id, mouseX, mouseY, x1, y1, x2, y2);
        gfx.fill(x1, y1, x2, y2, WbPalette.mix(c("raised"), c("diff-remove-bg"), h));
        outline(gfx, x1, y1, x2, y2, c("danger"));
        gfx.drawString(font, label, x1 + (x2 - x1 - font.width(label)) / 2, y1 + (y2 - y1 - 8) / 2, c("danger"), false);
        hits.add(new Hit(x1, y1, x2, y2, action));
    }
}
