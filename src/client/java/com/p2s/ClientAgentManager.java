package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.context.ContextBuilder;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.Agent;
import com.p2s.agent.core.AgentEvent;
import com.p2s.agent.core.AgentLoop;
import com.p2s.agent.core.AgentLoopConfig;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentToolResult;
import com.p2s.agent.core.AssistantMessageEvent;
import com.p2s.agent.llm.OpenAiChatCompletions;
import com.p2s.agent.llm.OpenAiMessageCodec;
import com.p2s.agent.runtime.AgentRunGuard;
import com.p2s.agent.runtime.AgentRunGuard.RunIdentity;
import com.p2s.agent.tool.RegistryTools;
import com.p2s.agent.tool.ToolExecutors;
import com.p2s.agent.tool.ToolRegistry;
import com.p2s.store.SessionPersistence;
import com.p2s.store.SkillStore;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Minecraft bridge for the main agent. Each chat session owns one {@link Agent} (the pi-style
 * core) and this class translates between it and the client: UI commands become prompts,
 * steering messages or aborts; agent events become chat, tool-log and status updates on the
 * client thread. Runs are fenced by {@link AgentRunGuard} so late callbacks of a closed or
 * replaced run never touch the current view.
 */
public final class ClientAgentManager {
    private static final Gson GSON = new Gson();
    private static final ExecutorService AGENT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "p2s-client-agent");
        t.setDaemon(true);
        return t;
    });
    /** Messages sent to the model per request (the stored transcript may be longer). */
    private static final int MAX_CONTEXT_MESSAGES = 40;
    /** Upper bound of the stored transcript between compactions. */
    private static final int MAX_TRANSCRIPT_MESSAGES = 400;
    private static final int SAFETY_TURN_LIMIT = 50;
    private static final int MAX_PLAN_ITEMS = 40;
    private static final int MAX_CHOICE_OPTIONS = 3;
    private static final String CLIENT_TOOL_CONTRACT = """
            ## Client Agent Contract
            - Start with get_project_state for current-project context before editing files.
            - Use list_skills to inspect available player skills by name/description.
            - Read full skill text only when needed via read_skill.
            - Read focused skill sub-documents via read_subdoc when read_skill exposes subdocs.
            - Use search_skill to locate relevant snippets quickly before reading full body.
            - Use update_plan to record user-visible progress with an optional explanation and ordered steps.
            - Each update_plan step must use one of: pending, in_progress, completed.
            - update_plan is for progress tracking only; it does not replace actually doing the work.
            - Before asking the user to choose among alternatives, call request_user_choice.
            - After request_user_choice, wait for user selection before continuing execution.
            - Tool calls operate on the current project implicitly; never send project_id.
            - File tools target workspace files by path only. Always pass path explicitly to read_workspace_file and propose_patch.
            - `read_workspace_file` returns the editable workspace body in `state.workspace_toml`.
            - `propose_patch` must send `{path, patch_toml}`; write actual patch operations as TOML inside `patch_toml`.
            - Use `insert_part` when creating a brand-new part; `insert_actions` is only for appending actions to an existing part.
            - Use create_workspace_file / rename_workspace_file / delete_workspace_file for file management.
            - Propose edits with propose_patch and wait for user apply/discard decision.
            - A successful propose_patch or request_user_choice pauses your turn; later calls in the same batch are not executed.
            - Use search_block_ids when unsure about block id names.
            - Use list_profiles/get_profile before creating subagents when profile choice matters.
            - Use create_subagent for delegated tasks. You are notified automatically when a subagent finishes; use get_subagent for its full result instead of polling repeatedly.
            - Use continue_subagent to continue a failed/completed/cancelled subagent with new instructions.
            - Use list_subagents to inspect current subagent states, and delete_subagent to stop/remove one.
            - Do not request recursive subagent creation from subagents.
            - The user may send new instructions while you work; they appear as user messages between your turns. Follow the latest instruction.
            """;
    private static final String CLIENT_TOOL_CONTRACT_V2 = """
            ## Client Agent Contract (tools v2)
            - Files: workspace/ holds this project's workspace files (TOML); skills/ and agents/ are read-only references. Start with project_info, then ls/read/grep/find.
            - Change workspace files with edit (exact text replacement), write (whole file), set_part (one part's actions), sh (bash-like text commands) or script (JavaScript: loops, geometry via geo.*, many tool calls in one go).
            - Every change is staged, not built. When your turn ends the player reviews all staged files and applies or discards them; you then receive one message with their decisions. Do not ask the player to apply in the middle of your work; finish, then summarize what you staged.
            - read returns the staged version, so later edits build on earlier ones. If a write fails because the file changed, read it again and redo the edit.
            - Workspace TOML: a [workspace] header (do not change origin, size or type), [palette], and [[part]] tables with name and priority, each holding [[part.action]] entries: type box|plane|line|points, block, from/to (box, plane, line) or at (points: list of [x,y,z]), mode (box: solid|shell|walls; plane: solid|outline), axis (plane: x|y|z with from[axis] == to[axis]), facing. Coordinates are relative to the workspace origin.
            - Change only the parts the task needs; keep unrelated parts untouched and give new parts clear names.
            - For curved or repetitive shapes use script with geo.* instead of writing hundreds of points by hand. When you would otherwise make many small tool calls, use script with batch(...) or sh.
            - Skills may mention propose_patch, patch_toml, read_skill or read_workspace_file; those tools are not available. Read skills as files under skills/ and apply their building rules with the tools above.
            - Use inspect_world to see what is actually built before modifying an existing structure.
            - Use update_plan for user-visible progress. Use ask_user before choosing between real alternatives; your turn pauses until the player answers.
            - Use task to delegate work to a subagent; you are notified automatically when it finishes. Use task_status for its full result.
            - The user may send new instructions while you work; they appear as user messages between your turns. Follow the latest instruction.
            """;

    private static final Object LOCK = new Object();
    private static LocalSession currentSession;
    private static boolean autoRestoreAttempted = false;
    private static long connectionEpoch;
    private static final Map<String, SessionPersistence.SavedSession> PENDING_SAVES = new ConcurrentHashMap<>();
    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "p2s-session-save");
        t.setDaemon(true);
        return t;
    });

    private ClientAgentManager() {
    }

    // ---------------------------------------------------------------- UI commands

    public static boolean isAgentRunning() {
        synchronized (LOCK) {
            return currentSession != null && currentSession.runs.isRunning();
        }
    }

    /** Stop the current run: abort the model request, skip unstarted tools, cancel subagents. */
    public static void stopCurrentRun() {
        LocalSession session;
        synchronized (LOCK) {
            session = currentSession;
            if (session == null || !session.runs.isRunning()) {
                return;
            }
            AbortSignal runSignal = session.runSignal;
            if (runSignal != null) {
                runSignal.abort("Stopped by user");
            }
            session.agent.abort("Stopped by user");
        }
        SubagentManager.cancelSession(session.id);
        postToClient(() -> ClientSessionState.setStatus("stopping"));
    }

    /** Polled by the chat screen every frame, so it only checks for a non-empty transcript. */
    public static boolean canManualCompact() {
        synchronized (LOCK) {
            return currentSession != null
                    && !currentSession.runs.isRunning()
                    && !currentSession.agent.messages().isEmpty();
        }
    }

    public static void submitManualCompact() {
        LocalSession session;
        RunIdentity run;
        synchronized (LOCK) {
            if (currentSession == null) {
                postToClient(() -> ClientSessionState.addSystemMessage(P2SI18n.tr("screen.p2s.chat.no_active_session").getString()));
                return;
            }
            if (currentSession.runs.isRunning()) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            if (!ClientHistoryCompactor.hasCompactableHistory(wireHistory(currentSession))) {
                postToClient(() -> {
                    ClientSessionState.addSystemMessage(P2SI18n.tr("message.p2s.compact.nothing_to_compact").getString());
                    ClientSessionState.setStatus("done");
                });
                return;
            }
            session = currentSession;
            run = beginRunLocked(session);
        }
        postToClient(session, run, () -> ClientSessionState.setStatus("compacting"));
        startRun(session, run, () -> {
            boolean compacted = performCompaction(session, run, ClientHistoryCompactor.CompactTrigger.MANUAL, false);
            if (!compacted) {
                postToClient(session, run, () -> ClientSessionState.setStatus("done"));
            }
            return AgentEvent.EndReason.COMPLETED;
        });
    }

    public static void submitUserMessage(String text) {
        submitUserMessage(text, text);
    }

    public static void submitUserMessage(String text, String displayText) {
        String msg = text == null ? "" : text.trim();
        String visible = displayText == null ? "" : displayText.trim();
        if (msg.isBlank()) {
            return;
        }
        if (visible.isBlank()) {
            visible = msg;
        }
        if (ClientSessionState.hasAnyPendingPatch()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "Pending patch awaiting decision. Use Apply or Discard first.",
                    false,
                    "awaiting_confirm"
            ));
            return;
        }
        if (ClientSessionState.hasPendingChoice()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "Pending choice awaiting selection. Pick one option first.",
                    false,
                    "awaiting_choice"
            ));
            return;
        }

        String finalVisible = visible;
        LocalSession session;
        RunIdentity run;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session == null) {
                return;
            }
            if (session.runs.isRunning()) {
                // pi steering: delivered after the current turn's tool calls finish.
                session.agent.steer(AgentMessage.User.of(msg));
                postToClient(() -> {
                    ClientSessionState.addUserMessage(finalVisible);
                    ClientSessionState.addSystemMessage(P2SI18n.tr("message.p2s.agent.queued").getString());
                });
                return;
            }
            run = beginRunLocked(session);
        }

        postToClient(session, run, () -> {
            ClientSessionState.addUserMessage(finalVisible);
            ClientSessionState.setStatus("planning");
        });
        startRun(session, run, () -> {
            maybeRunAutomaticCompaction(session, run, ClientHistoryCompactor.CompactTrigger.AUTO_PRE_TURN);
            if (!isCurrentRun(session, run) || isRunAborted(session)) {
                return AgentEvent.EndReason.ABORTED;
            }
            return promptAgent(session, run, List.of(AgentMessage.User.of(msg)));
        });
    }

    /** Apply the selected pending file. */
    public static void submitPatchApply() {
        submitPatchDecision(true, "", false);
    }

    /** Apply every file with a staged change (Shift+Apply and auto-apply). */
    public static void submitPatchApplyAll() {
        submitPatchDecision(true, "", true);
    }

    public static void submitPatchDiscard(String reason) {
        submitPatchDecision(false, reason, false);
    }

    /**
     * Apply / Discard. The server commits or drops each file and returns a receipt; only receipts
     * reach the model, so "applied" always means committed. While other files still have staged
     * changes the agent is not woken: decisions are collected and reported in one message once
     * nothing is left to review, so the agent never works on top of half-reviewed changes.
     */
    private static void submitPatchDecision(boolean apply, String reason, boolean all) {
        List<String> paths = all ? ClientSessionState.getPendingWorkspacePaths()
                : ClientSessionState.hasPendingPatch() ? List.of(ClientSessionState.getPendingPath()) : List.of();
        if (paths.isEmpty()) {
            return;
        }
        String selectedSummary = ClientSessionState.getPendingSummary();
        String safeReason = reason == null ? "" : reason.trim();

        LocalSession session;
        RunIdentity run;
        PendingApproval approval;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session == null) {
                return;
            }
            if (session.runs.isRunning()) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            run = beginRunLocked(session);
            approval = session.pendingApproval;
        }

        String visible = (apply ? "Applied" : "Discarded") + (paths.size() > 1 ? " " + paths.size() + " files" : " patch: " + selectedSummary)
                + (safeReason.isEmpty() ? "" : " (Reason: " + safeReason + ")");
        postToClient(session, run, () -> {
            ClientSessionState.addUserMessage(visible);
            ClientSessionState.setStatus(apply ? "applying" : "planning");
        });

        startRun(session, run, () -> {
            int failures = 0;
            for (String path : paths) {
                JsonObject args = new JsonObject();
                if (path != null && !path.isBlank()) {
                    args.addProperty("path", path);
                }
                if (apply && approval != null && approval.path().equals(path) && !approval.revision().isBlank()) {
                    args.addProperty("expected_revision", approval.revision());
                }
                if (!apply && !safeReason.isBlank()) {
                    args.addProperty("reason", safeReason);
                }
                JsonObject receipt = callServerTool(session, run, apply ? "session_apply_patch" : "session_discard_patch", args);
                if (!isCurrentRun(session, run) || isRunAborted(session)) {
                    return AgentEvent.EndReason.ABORTED;
                }
                if (AgentToolResult.isErrorPayload(receipt)) {
                    failures++;
                    String error = asString(receipt, "error");
                    postToClient(session, run, () -> ClientSessionState.onChatResponse(
                            P2SI18n.tr(apply ? "message.p2s.agent.apply_failed" : "message.p2s.agent.discard_failed",
                                    path + ": " + (error.isBlank() ? "unknown error" : error)).getString(),
                            false, "error"));
                    continue;
                }
                String shown = ClientVirtualFs.toVfs(asString(receipt, "path").isBlank() ? path : asString(receipt, "path"));
                String summary = asString(receipt, "summary");
                String decision = apply
                        ? "APPLIED " + shown + " (committed as revision " + asString(receipt, "revision") + ")"
                        : "DISCARDED " + shown + (safeReason.isEmpty() ? "" : " - reason: " + safeReason);
                synchronized (LOCK) {
                    session.reviewDecisions.add(decision + (summary.isBlank() ? "" : ": " + summary));
                    if (approval != null && approval.path().equals(path)) {
                        session.pendingApproval = null;
                    }
                }
            }
            if (failures == paths.size()) {
                return AgentEvent.EndReason.ERROR;
            }
            JsonObject state = callServerTool(session, run, "get_project_state", new JsonObject());
            java.util.Set<String> remaining = ClientVirtualFs.pendingPaths(state);
            if (!AgentToolResult.isErrorPayload(state) && !remaining.isEmpty()) {
                int left = remaining.size();
                postToClient(session, run, () -> ClientSessionState.addSystemMessage(
                        P2SI18n.tr("message.p2s.agent.review_remaining", left).getString()));
                return AgentEvent.EndReason.COMPLETED;
            }
            List<String> decisions;
            synchronized (LOCK) {
                decisions = List.copyOf(session.reviewDecisions);
                session.reviewDecisions.clear();
            }
            if (decisions.isEmpty()) {
                return AgentEvent.EndReason.COMPLETED;
            }
            StringBuilder history = new StringBuilder("Player review of your staged changes:");
            for (String decision : decisions) {
                history.append("\n- ").append(decision);
            }
            boolean anyDiscarded = decisions.stream().anyMatch(d -> d.startsWith("DISCARDED"));
            history.append(anyDiscarded ? "\nRevise the discarded changes, then continue." : "\nContinue with the next step.");
            postToClient(session, run, () -> ClientSessionState.setStatus("planning"));
            return promptAgent(session, run, List.of(AgentMessage.User.of(history.toString())));
        });
    }

    public static void submitChoiceSelection(String optionId) {
        String selectedId = optionId == null ? "" : optionId.trim();
        if (selectedId.isBlank()) {
            return;
        }

        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null || choice.options() == null || choice.options().isEmpty()) {
            postToClient(() -> ClientSessionState.setStatus("No pending choice"));
            return;
        }

        ClientSessionState.ChoiceOption selected = null;
        for (ClientSessionState.ChoiceOption option : choice.options()) {
            if (option != null && selectedId.equals(option.id())) {
                selected = option;
                break;
            }
        }
        if (selected == null) {
            postToClient(() -> ClientSessionState.setStatus("error"));
            return;
        }

        submitChoiceResponse(
                choice,
                "Choice selected [" + selected.id() + "]: " + selected.label(),
                "User selected option " + selected.id() + " (" + selected.label() + ") for request: " + choice.prompt()
        );
    }

    public static void submitCustomChoice(String customText) {
        String custom = customText == null ? "" : customText.trim();
        if (custom.isBlank()) {
            return;
        }

        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null || choice.options() == null || choice.options().isEmpty()) {
            postToClient(() -> ClientSessionState.setStatus("No pending choice"));
            return;
        }

        submitChoiceResponse(
                choice,
                "Custom choice: " + custom,
                "User provided a custom response for request: " + choice.prompt() + "\nCustom response: " + custom
        );
    }

    private static void submitChoiceResponse(
            ClientSessionState.ChoiceRequest choice,
            String userMessageText,
            String historyContent
    ) {
        if (choice == null) {
            return;
        }
        LocalSession session;
        RunIdentity run;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session == null) {
                return;
            }
            if (session.runs.isRunning()) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            run = beginRunLocked(session);
            ClientSessionState.clearPendingChoice();
        }

        postToClient(session, run, () -> {
            ClientSessionState.addUserMessage(userMessageText);
            ClientSessionState.setStatus("planning");
        });
        String content = historyContent == null ? "" : historyContent;
        startRun(session, run, () -> promptAgent(session, run, List.of(AgentMessage.User.of(content))));
    }

    /**
     * A subagent of the current session finished. While the main agent runs, the notice is
     * steered into its next turn; while it waits for the user, the notice is queued for the
     * next run; when idle, it starts a run so delegated work continues without polling.
     */
    static void onSubagentFinished(String sessionId, String subagentId, String notice) {
        postToClient(() -> {
            LocalSession session;
            RunIdentity run;
            synchronized (LOCK) {
                session = currentSession;
                if (session == null || sessionId == null || !sessionId.equals(session.id)) {
                    return;
                }
                AgentMessage.User message = AgentMessage.User.of(notice);
                if (session.runs.isRunning() || ClientSessionState.hasAnyPendingPatch()
                        || ClientSessionState.hasPendingChoice()) {
                    session.agent.steer(message);
                    return;
                }
                run = beginRunLocked(session);
            }
            postToClient(session, run, () -> {
                ClientSessionState.addSystemMessage(
                        P2SI18n.tr("message.p2s.agent.subagent_resume", subagentId).getString());
                ClientSessionState.setStatus("planning");
            });
            startRun(session, run, () -> promptAgent(session, run, List.of(AgentMessage.User.of(notice))));
        });
    }

    // ---------------------------------------------------------------- run lifecycle

    private static RunIdentity beginRunLocked(LocalSession session) {
        RunIdentity run = session.runs.beginRun();
        session.runSignal = AbortSignal.create();
        session.activeRun = run;
        return run;
    }

    private static boolean isRunAborted(LocalSession session) {
        synchronized (LOCK) {
            return session.runSignal != null && session.runSignal.isAborted();
        }
    }

    @FunctionalInterface
    private interface RunBody {
        AgentEvent.EndReason run() throws Exception;
    }

    private static void startRun(LocalSession session, RunIdentity run, RunBody body) {
        AGENT_EXECUTOR.execute(() -> {
            AgentEvent.EndReason reason = AgentEvent.EndReason.ERROR;
            try {
                reason = body.run();
            } catch (Exception ex) {
                String error = formatAgentError(ex);
                postToClient(session, run, () -> ClientSessionState.onChatResponse("Request failed: " + error, false, "error"));
            } finally {
                finishRun(session, run, reason);
            }
        });
    }

    /** Runs the core agent for one prompt and bridges its events to the client. */
    private static AgentEvent.EndReason promptAgent(LocalSession session, RunIdentity run, List<AgentMessage> prompts) {
        if (!isCurrentRun(session, run)) {
            return AgentEvent.EndReason.ABORTED;
        }
        session.agent.setSystemPrompt(systemPrompt());
        if (P2SClientConfig.isToolsetV2()) {
            session.agent.setTools(AgentToolsV2.create(v2Host(session, run), null).agentTools(
                    () -> isCurrentRun(session, run), (name, payload) -> interactionControl(session, name, payload)));
        } else {
            session.agent.setTools(RegistryTools.from(createToolRegistry(session, run),
                    () -> isCurrentRun(session, run), (name, payload) -> interactionControl(session, name, payload)));
        }
        Runnable unsubscribe = session.agent.subscribe(event -> onAgentEvent(session, run, event));
        try {
            if (isRunAborted(session)) {
                return AgentEvent.EndReason.ABORTED;
            }
            AgentLoop.Outcome outcome = session.agent.prompt(prompts);
            return outcome.reason();
        } finally {
            unsubscribe.run();
        }
    }

    /** Successful approvals and choices hand control to the user and pause the run. */
    private static AgentToolResult.Control interactionControl(LocalSession session, String toolName, JsonObject payload) {
        if ("propose_patch".equals(toolName)) {
            String path = asString(payload, "path");
            String revision = asString(payload, "pending_revision");
            if (session != null) {
                synchronized (LOCK) {
                    session.pendingApproval = new PendingApproval(path, revision);
                }
            }
            return AgentToolResult.Control.PAUSE;
        }
        if ("request_user_choice".equals(toolName) || "ask_user".equals(toolName)) {
            return AgentToolResult.Control.PAUSE;
        }
        return AgentToolResult.Control.NONE;
    }

    private static void onAgentEvent(LocalSession session, RunIdentity run, AgentEvent event) {
        if (event instanceof AgentEvent.MessageStart start && start.message() instanceof AgentMessage.Assistant) {
            if (P2SClientConfig.getUseStreaming()) {
                postToClient(session, run, ClientSessionState::beginStreaming, false);
            }
            postToClient(session, run, () -> ClientSessionState.setStatus("thinking"), false);
        } else if (event instanceof AgentEvent.MessageUpdate update
                && update.event() instanceof AssistantMessageEvent.TextDelta delta) {
            postToClient(session, run, () -> ClientSessionState.appendStreamingToken(delta.delta()), false);
        } else if (event instanceof AgentEvent.MessageEnd end && end.message() instanceof AgentMessage.Assistant assistant) {
            postToClient(session, run, ClientSessionState::endStreaming, false);
            if (assistant.stopReason() == AgentMessage.StopReason.ABORTED) {
                postToClient(session, run, () -> ClientSessionState.addSystemMessage(
                        P2SI18n.tr("message.p2s.agent.stopped").getString()));
            } else if (assistant.failed()) {
                String error = assistant.errorMessage().isBlank() ? "Unknown error" : assistant.errorMessage();
                postToClient(session, run, () -> ClientSessionState.onChatResponse("Request failed: " + error, false, "error"));
            } else if (!assistant.text().isBlank()) {
                String text = assistant.text();
                postToClient(session, run, () -> ClientSessionState.onChatResponse(text, false, null));
            }
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] ClientAgent assistant -> stop={}, toolCalls={}, textLen={}",
                        assistant.stopReason(), assistant.toolCalls().size(), assistant.text().length());
            }
        } else if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
            AgentMessage.ToolCall call = toolEnd.toolCall();
            JsonObject resultPayload = toolEnd.result().content();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] ClientAgent tool call -> name={}, args={}, result={}",
                        call.name(), call.rawArguments(), GSON.toJson(resultPayload));
            }
            postToClient(session, run, () -> {
                ToolLogPresentation presentation = buildToolLogPresentation(call, resultPayload);
                if (presentation.error()) {
                    ClientSessionState.addToolErrorMessage(presentation.summary(), presentation.detail());
                } else {
                    ClientSessionState.addToolCallMessage(presentation.summary(), presentation.detail());
                }
            });
        } else if (event instanceof AgentEvent.TurnEnd turnEnd && !turnEnd.toolResults().isEmpty()) {
            postToClient(session, run, () -> ClientSessionState.setStatus(statusAfterToolCalls()));
        }
    }

    private static void finishRun(LocalSession session, RunIdentity run, AgentEvent.EndReason reason) {
        postToClient(() -> {
            synchronized (LOCK) {
                if (!isCurrentRunLocked(session, run) || !session.runs.finishRun(run)) {
                    return;
                }
                session.activeRun = null;
                session.runSignal = null;
                boolean sameProject = session.projectId.equals(ClientSessionState.getProjectId());
                if (sameProject) {
                    // Queued after the final text/tool/UI updates of this run.
                    ClientSessionState.endStreaming();
                    ClientSessionState.setStatus(statusAfterRun(reason));
                    if (reason == AgentEvent.EndReason.STOPPED_BY_POLICY) {
                        ClientSessionState.addSystemMessage(
                                P2SI18n.tr("message.p2s.agent.safety_limit", SAFETY_TURN_LIMIT).getString());
                    }
                }
                capTranscriptLocked(session);
                saveSessionLocked(session);
                if (!sameProject) {
                    return;
                }
                boolean waitingForUser = ClientSessionState.hasAnyPendingPatch() || ClientSessionState.hasPendingChoice();
                if (reason != AgentEvent.EndReason.ABORTED && reason != AgentEvent.EndReason.PAUSED
                        && !waitingForUser && session.agent.hasQueuedMessages()) {
                    // Messages steered after the agent's last check: run them now instead of
                    // leaving them invisible to the model until the next user action.
                    List<AgentMessage> queued = new ArrayList<>(session.agent.clearSteeringQueue());
                    queued.addAll(session.agent.clearFollowUpQueue());
                    RunIdentity next = beginRunLocked(session);
                    ClientSessionState.setStatus("planning");
                    startRun(session, next, () -> promptAgent(session, next, queued));
                    return;
                }
                // Not after a failed run: a rejected auto-apply would otherwise retry forever.
                if (reason != AgentEvent.EndReason.ERROR) {
                    maybeAutoApplyPendingPatch();
                }
            }
        });
    }

    private static String statusAfterRun(AgentEvent.EndReason reason) {
        return switch (reason) {
            case PAUSED, COMPLETED -> {
                String waiting = statusAfterToolCalls();
                yield "thinking".equals(waiting) ? "done" : waiting;
            }
            case ABORTED -> "stopped";
            case ERROR, STOPPED_BY_POLICY -> "error";
        };
    }

    // ---------------------------------------------------------------- compaction

    private static boolean maybeRunAutomaticCompaction(LocalSession session, RunIdentity run,
                                                       ClientHistoryCompactor.CompactTrigger trigger) {
        if (!P2SClientConfig.isAutoCompactEnabled()) {
            return false;
        }
        List<JsonObject> wire = wireHistory(session);
        if (!ClientHistoryCompactor.hasCompactableHistory(wire)
                || !ClientHistoryCompactor.hasNonSummaryNonSystemMessages(wire)
                || ClientHistoryCompactor.estimateHistoryTokens(wire) < P2SClientConfig.getAutoCompactTokenLimit()) {
            return false;
        }
        return performCompaction(session, run, trigger, true);
    }

    /** Mid-run compaction hook (pi prepareNextTurn); returns the replacement transcript or null. */
    private static List<AgentMessage> compactBetweenTurns(LocalSession session, List<AgentMessage> messages) {
        RunIdentity run;
        synchronized (LOCK) {
            run = session.activeRun;
        }
        if (run == null || !P2SClientConfig.isAutoCompactEnabled()) {
            return null;
        }
        List<JsonObject> wire = OpenAiMessageCodec.toWire(systemPrompt(), ContextBuilder.repair(messages));
        if (!ClientHistoryCompactor.hasNonSummaryNonSystemMessages(wire)
                || ClientHistoryCompactor.estimateHistoryTokens(wire) < P2SClientConfig.getAutoCompactTokenLimit()) {
            return null;
        }
        CompactionOutcome outcome = compact(session, run, wire, ClientHistoryCompactor.CompactTrigger.AUTO_MID_TURN, true);
        return outcome == null ? null : outcome.messages();
    }

    /** Compaction while the agent is idle (manual or before a turn). */
    private static boolean performCompaction(LocalSession session, RunIdentity run,
                                             ClientHistoryCompactor.CompactTrigger trigger, boolean requireReduction) {
        CompactionOutcome outcome = compact(session, run, wireHistory(session), trigger, requireReduction);
        if (outcome == null) {
            return false;
        }
        session.agent.replaceMessages(outcome.messages());
        return true;
    }

    private static CompactionOutcome compact(LocalSession session, RunIdentity run, List<JsonObject> wire,
                                             ClientHistoryCompactor.CompactTrigger trigger, boolean requireReduction) {
        if (!isCurrentRun(session, run)) {
            return null;
        }
        AbortSignal signal;
        synchronized (LOCK) {
            signal = session.runSignal == null ? AbortSignal.none() : session.runSignal;
        }
        postToClient(session, run, () -> ClientSessionState.setStatus("compacting"));
        try {
            ClientHistoryCompactor.CompactionResult result = ClientHistoryCompactor.compactHistory(
                    wire, trigger, P2SClientConfig.llmRequestConfig(), signal);
            if (requireReduction && result.estimatedTokensAfter() >= result.estimatedTokensBefore()) {
                return null;
            }
            if (!isCurrentRun(session, run)) {
                return null;
            }
            List<AgentMessage> replacement = OpenAiMessageCodec.fromWire(result.replacementHistory()).messages();
            postToClient(session, run, () -> {
                ClientSessionState.addSystemMessage(P2SI18n.tr("message.p2s.compact.done").getString() + "\n" + result.summaryBody());
                if (trigger == ClientHistoryCompactor.CompactTrigger.MANUAL) {
                    ClientSessionState.setStatus("done");
                }
            });
            return new CompactionOutcome(replacement);
        } catch (Exception ex) {
            String error = formatAgentError(ex);
            P2SMod.LOGGER.warn("History compaction failed ({}): {}", trigger, error);
            postToClient(session, run, () -> ClientSessionState.addSystemMessage(
                    P2SI18n.tr("message.p2s.compact.failed_fallback").getString() + " " + error
            ));
            return null;
        }
    }

    private record CompactionOutcome(List<AgentMessage> messages) {
    }

    // ---------------------------------------------------------------- agent wiring

    private static Agent createAgent(LocalSession session, List<AgentMessage> transcript) {
        AgentLoopConfig config = AgentLoopConfig.builder(new OpenAiChatCompletions(P2SClientConfig::llmRequestConfig))
                .transformContext(messages -> ContextBuilder.build(messages, MAX_CONTEXT_MESSAGES,
                        ClientAgentManager::isPinnedMessage))
                .prepareNextTurn(turn -> compactBetweenTurns(session, turn.messages()))
                .shouldStopAfterTurn(turn -> turn.turnIndex() + 1 >= SAFETY_TURN_LIMIT)
                .toolExecutor(ToolExecutors.shared())
                .build();
        return new Agent(config, systemPrompt(), List.of(), transcript);
    }

    private static String systemPrompt() {
        return P2SClientConfig.getSystemPrompt() + "\n\n"
                + (P2SClientConfig.isToolsetV2() ? CLIENT_TOOL_CONTRACT_V2 : CLIENT_TOOL_CONTRACT);
    }

    /** v2 tools for the main agent of {@code session}, fenced by {@code run}. */
    private static AgentToolsV2.Host v2Host(LocalSession session, RunIdentity run) {
        return new AgentToolsV2.Host() {
            @Override
            public JsonObject server(String tool, JsonObject args) {
                return callServerTool(session, run, tool, args);
            }

            @Override
            public JsonObject onClient(Supplier<JsonObject> action) {
                return callOnClient(session, run, action);
            }

            @Override
            public String sessionId() {
                return ClientAgentManager.sessionId(session);
            }

            @Override
            public Set<String> skillScope() {
                return Set.of();
            }

            @Override
            public Map<String, JsonElement> scriptStore() {
                return session.scriptStore;
            }

            @Override
            public boolean isSubagent() {
                return false;
            }
        };
    }

    private static boolean isPinnedMessage(AgentMessage message) {
        return message instanceof AgentMessage.User user && ClientHistoryCompactor.isSummaryContent(user.content());
    }

    private static List<JsonObject> wireHistory(LocalSession session) {
        return OpenAiMessageCodec.toWire(systemPrompt(), ContextBuilder.repair(session.agent.messages()));
    }

    private static void capTranscriptLocked(LocalSession session) {
        if (session.agent.isRunning()) {
            return;
        }
        List<AgentMessage> messages = session.agent.messages();
        if (messages.size() > MAX_TRANSCRIPT_MESSAGES) {
            session.agent.replaceMessages(ContextBuilder.limit(ContextBuilder.repair(messages),
                    MAX_TRANSCRIPT_MESSAGES, ClientAgentManager::isPinnedMessage));
        }
    }

    private static ToolRegistry createToolRegistry(LocalSession session, RunIdentity run) {
        return ToolRegistry.builder()
                .register("list_skills", call -> listSkillsPayload())
                .register("read_skill", call -> readSkillPayload(call.arguments()))
                .register("read_subdoc", call -> readSubdocPayload(call.arguments()))
                .register("search_skill", call -> searchSkillPayload(call.arguments()))
                .register("update_plan", call -> callOnClient(session, run, () -> updatePlanPayload(call.arguments())))
                .register("request_user_choice", call -> callOnClient(session, run, () -> requestUserChoicePayload(call.arguments())))
                .register("clear_user_choice", call -> callOnClient(session, run, ClientAgentManager::clearUserChoicePayload))
                .register("list_subagents", call -> listSubagentsPayload(session, call.arguments()))
                .register("create_subagent", call -> callOnClient(session, run, () -> createSubagentPayload(session, call.arguments())))
                .register("continue_subagent", call -> callOnClient(session, run, () -> continueSubagentPayload(session, call.arguments())))
                .register("get_subagent", call -> getSubagentPayload(session, call.arguments()))
                .register("delete_subagent", call -> deleteSubagentPayload(session, call.arguments()))
                .register("list_profiles", call -> SubagentManager.listProfiles())
                .register("get_profile", call -> getProfilePayload(call.arguments()))
                .register("get_project_state", call -> callServerTool(session, run, call.name(), normalizeArgsObject(call.arguments())))
                .register("read_workspace_file", call -> callServerTool(session, run, call.name(), normalizeArgsObject(call.arguments())))
                .register("create_workspace_file", call -> callServerTool(session, run, call.name(), normalizeArgsObject(call.arguments())))
                .register("rename_workspace_file", call -> callServerTool(session, run, call.name(), normalizeArgsObject(call.arguments())))
                .register("delete_workspace_file", call -> callServerTool(session, run, call.name(), normalizeArgsObject(call.arguments())))
                .register("propose_patch", call -> callServerTool(session, run, call.name(), normalizeArgsObject(call.arguments())))
                .register("search_block_ids", call -> callServerTool(session, run, call.name(), normalizeArgsObject(call.arguments())))
                .build();
    }

    // ---------------------------------------------------------------- tool handlers

    private static JsonObject listSubagentsPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        boolean includeDeleted = asBoolean(args, "include_deleted", false);
        return SubagentManager.listSubagents(sessionId(session), includeDeleted);
    }

    private static JsonObject createSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        return SubagentManager.createSubagent(sessionId(session), args);
    }

    private static JsonObject continueSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("continue_subagent", "Missing id");
        }
        return SubagentManager.continueSubagent(sessionId(session), args);
    }

    private static JsonObject getSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("get_subagent", "Missing id");
        }
        return SubagentManager.getSubagent(sessionId(session), id);
    }

    private static JsonObject deleteSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("delete_subagent", "Missing id");
        }
        return SubagentManager.deleteSubagent(sessionId(session), id);
    }

    private static JsonObject getProfilePayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("get_profile", "Missing id");
        }
        return SubagentManager.getProfile(id);
    }

    private static JsonObject listSkillsPayload() {
        JsonObject payload = toolOk("list_skills");
        String active = SkillStore.activeSkillId();
        payload.addProperty("active_skill_id", active == null ? "" : active);
        JsonArray items = new JsonArray();
        for (SkillStore.SkillMeta meta : SkillStore.listSkills()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", meta.id());
            item.addProperty("name", meta.name());
            item.addProperty("description", meta.description());
            items.add(item);
        }
        payload.add("skills", items);
        payload.addProperty("count", items.size());
        return payload;
    }

    private static JsonObject readSkillPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            id = SkillStore.activeSkillId();
        }
        if (id.isBlank()) {
            return toolError("read_skill", "No skill id and no active skill");
        }
        SkillStore.SkillDocument doc = SkillStore.readSkill(id);
        if (doc == null) {
            return toolError("read_skill", "Skill not found: " + id);
        }
        JsonObject payload = toolOk("read_skill");
        payload.addProperty("id", doc.meta().id());
        payload.addProperty("name", doc.meta().name());
        payload.addProperty("description", doc.meta().description());
        payload.addProperty("body", doc.body() == null ? "" : doc.body());
        JsonArray subdocs = new JsonArray();
        for (SkillStore.SubdocMeta subdoc : SkillStore.listSubdocs(doc.meta().id())) {
            JsonObject item = new JsonObject();
            item.addProperty("path", subdoc.path());
            item.addProperty("updated_at", subdoc.updatedAt());
            subdocs.add(item);
        }
        payload.add("subdocs", subdocs);
        payload.addProperty("subdoc_count", subdocs.size());
        return payload;
    }

    private static JsonObject readSubdocPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String path = asString(args, "path");
        if (id.isBlank()) {
            id = SkillStore.activeSkillId();
        }
        if (id.isBlank()) {
            return toolError("read_subdoc", "No skill id and no active skill");
        }
        if (path.isBlank()) {
            return toolError("read_subdoc", "Missing path");
        }
        SkillStore.SubdocDocument doc = SkillStore.readSubdoc(id, path);
        if (doc == null) {
            return toolError("read_subdoc", "Subdoc not found: " + id + "/" + path);
        }
        JsonObject payload = toolOk("read_subdoc");
        payload.addProperty("id", doc.skillId());
        payload.addProperty("path", doc.path());
        payload.addProperty("body", doc.body() == null ? "" : doc.body());
        payload.addProperty("updated_at", doc.updatedAt());
        return payload;
    }

    private static JsonObject searchSkillPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String query = asString(args, "query");
        int limit = asInt(args, "limit", 10, 1, 50);
        if (query.isBlank()) {
            return toolError("search_skill", "Missing query");
        }
        List<SkillStore.SearchHit> hits = SkillStore.searchSkill(id, query, limit);
        JsonObject payload = toolOk("search_skill");
        payload.addProperty("query", query);
        payload.addProperty("limit", limit);
        JsonArray arr = new JsonArray();
        for (SkillStore.SearchHit hit : hits) {
            JsonObject item = new JsonObject();
            item.addProperty("id", hit.skillId());
            item.addProperty("line", hit.line());
            item.addProperty("text", hit.text());
            arr.add(item);
        }
        payload.add("matches", arr);
        payload.addProperty("count", arr.size());
        if (arr.isEmpty()) {
            payload.addProperty("warning", "No matches");
        }
        return payload;
    }

    static JsonObject updatePlanPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        if (!args.has("plan") || !args.get("plan").isJsonArray()) {
            return toolError("update_plan", "Missing plan array");
        }

        String explanation = asString(args, "explanation");
        List<ClientSessionState.PlanItem> items = parsePlanItems(args.getAsJsonArray("plan"));
        ClientSessionState.setPlan(explanation, items);

        JsonObject payload = toolOk("update_plan");
        payload.addProperty("explanation", explanation == null ? "" : explanation.trim());
        payload.addProperty("count", items.size());
        return payload;
    }

    private static List<ClientSessionState.PlanItem> parsePlanItems(JsonArray itemsArg) {
        List<ClientSessionState.PlanItem> items = new ArrayList<>();
        for (JsonElement element : itemsArg) {
            if (items.size() >= MAX_PLAN_ITEMS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String content = asString(obj, "step");
            if (content.isBlank()) {
                continue;
            }
            String status = normalizePlanStatus(asString(obj, "status"));
            items.add(new ClientSessionState.PlanItem(content.trim(), status));
        }
        return items;
    }

    static JsonObject requestUserChoicePayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String prompt = asString(args, "prompt");
        if (prompt.isBlank()) {
            prompt = asString(args, "question");
        }
        if (prompt.isBlank()) {
            return toolError("request_user_choice", "Missing prompt");
        }
        if (!args.has("options") || !args.get("options").isJsonArray()) {
            return toolError("request_user_choice", "Missing options array");
        }

        JsonArray optionsArg = args.getAsJsonArray("options");
        List<ClientSessionState.ChoiceOption> options = new ArrayList<>();
        int index = 1;
        for (JsonElement element : optionsArg) {
            if (options.size() >= MAX_CHOICE_OPTIONS) {
                break;
            }
            String id = "";
            String label = "";
            String description = "";
            if (element != null && element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                id = normalizeStableId(asString(obj, "id"));
                label = asString(obj, "label");
                description = asString(obj, "description");
            } else if (element != null && element.isJsonPrimitive()) {
                label = element.getAsString();
            }
            if (label == null || label.isBlank()) {
                index += 1;
                continue;
            }
            if (id.isBlank()) {
                id = "opt-" + index;
            }
            options.add(new ClientSessionState.ChoiceOption(id, label.trim(), description == null ? "" : description.trim()));
            index += 1;
        }
        if (options.size() < 2) {
            return toolError("request_user_choice", "Need at least 2 valid options");
        }

        String requestId = asString(args, "request_id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "choice-" + Long.toString(System.currentTimeMillis(), 36);
        }
        String finalRequestId = requestId.trim();
        String finalPrompt = prompt.trim();
        List<ClientSessionState.ChoiceOption> finalOptions = List.copyOf(options);
        ClientSessionState.setPendingChoice(finalRequestId, finalPrompt, finalOptions);
        ClientSessionState.setStatus("awaiting_choice");

        JsonObject payload = toolOk("request_user_choice");
        payload.addProperty("request_id", finalRequestId);
        payload.addProperty("prompt", finalPrompt);
        payload.addProperty("count", finalOptions.size());
        return payload;
    }

    private static JsonObject clearUserChoicePayload() {
        ClientSessionState.clearPendingChoice();
        JsonObject payload = toolOk("clear_user_choice");
        payload.addProperty("cleared", true);
        return payload;
    }

    /**
     * Sends a server tool request and waits for its response while the run stays current.
     * When the run is stopped the wait ends early; a request already sent may still be
     * completed by the server, and the result says so.
     */
    private static JsonObject callServerTool(LocalSession session, RunIdentity run, String toolName, JsonObject args) {
        BooleanSupplier active = () -> isCurrentRun(session, run) && !isRunAborted(session);
        CompletableFuture<JsonObject> future = ClientToolBridge.call(toolName, args, active);
        try {
            while (true) {
                try {
                    JsonObject payload = future.get(100, TimeUnit.MILLISECONDS);
                    return payload == null ? toolError(toolName, "Empty server response") : payload;
                } catch (TimeoutException ignored) {
                    if (!active.getAsBoolean()) {
                        JsonObject cancelled = toolError(toolName,
                                "Stopped while waiting for the server; the request may still have been applied");
                        cancelled.addProperty("error_code", "cancelled");
                        return cancelled;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return toolError(toolName, "Interrupted");
        } catch (ExecutionException e) {
            return toolError(toolName, "Server tool failed: " + formatAgentError(e.getCause()));
        }
    }

    private static String statusAfterToolCalls() {
        if (ClientSessionState.hasPendingChoice()) {
            return "awaiting_choice";
        }
        if (ClientSessionState.hasPendingPatch()) {
            return "awaiting_confirm";
        }
        return "thinking";
    }

    private static String normalizePlanStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "pending";
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "completed" -> "completed";
            case "in_progress" -> "in_progress";
            case "pending" -> "pending";
            default -> "pending";
        };
    }

    private static String normalizeStableId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase();
        value = value.replaceAll("[^a-z0-9_-]+", "-");
        value = value.replaceAll("-{2,}", "-");
        value = value.replaceAll("^-+", "");
        value = value.replaceAll("-+$", "");
        return value;
    }

    private static JsonObject normalizeArgsObject(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return new JsonObject();
                }
                parsed = JsonParser.parseString(raw);
            }
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            return new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static int asInt(JsonObject obj, String key, int fallback, int min, int max) {
        if (obj == null || key == null || !obj.has(key)) {
            return fallback;
        }
        try {
            int value = obj.get(key).getAsInt();
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean asBoolean(JsonObject obj, String key, boolean fallback) {
        if (obj == null || key == null || !obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String asString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonObject toolOk(String toolName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        return payload;
    }

    private static JsonObject toolError(String toolName, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        payload.addProperty("error", error == null ? "" : error);
        return payload;
    }

    private static String formatAgentError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "Timed out";
        }
        String message = cause == null ? null : cause.getMessage();
        if (message == null || message.isBlank()) {
            message = ex == null ? "" : ex.getMessage();
        }
        return message == null || message.isBlank() ? "Unknown error" : message;
    }

    // ---------------------------------------------------------------- tool log presentation

    private record ToolLogPresentation(String summary, String detail, boolean error) {
    }

    private static ToolLogPresentation buildToolLogPresentation(AgentMessage.ToolCall call, JsonObject payload) {
        boolean error = payload == null || !asBoolean(payload, "ok", true);
        String summary = error
                ? P2SI18n.tr("message.p2s.agent.tool.failed", toolSummaryText(call)).getString()
                : toolSummaryText(call);
        String detail = buildToolDetail(call, payload);
        return new ToolLogPresentation(summary, detail, error);
    }

    private static String toolSummaryText(AgentMessage.ToolCall call) {
        String key = toolSummaryKey(call);
        if (key == null || key.isBlank()) {
            return rawToolName(call);
        }
        return P2SI18n.tr(key).getString();
    }

    private static String toolSummaryKey(AgentMessage.ToolCall call) {
        String toolName = rawToolName(call);
        return switch (toolName) {
            case "list_skills" -> "message.p2s.agent.tool.summary.list_skills";
            case "read_skill" -> "message.p2s.agent.tool.summary.read_skill";
            case "read_subdoc" -> "message.p2s.agent.tool.summary.read_subdoc";
            case "search_skill" -> "message.p2s.agent.tool.summary.search_skill";
            case "update_plan" -> "message.p2s.agent.tool.summary.update_plan";
            case "request_user_choice" -> "message.p2s.agent.tool.summary.request_user_choice";
            case "clear_user_choice" -> "message.p2s.agent.tool.summary.clear_user_choice";
            case "list_subagents" -> "message.p2s.agent.tool.summary.list_subagents";
            case "create_subagent" -> "message.p2s.agent.tool.summary.create_subagent";
            case "continue_subagent" -> "message.p2s.agent.tool.summary.continue_subagent";
            case "get_subagent" -> "message.p2s.agent.tool.summary.get_subagent";
            case "delete_subagent" -> "message.p2s.agent.tool.summary.delete_subagent";
            case "list_profiles" -> "message.p2s.agent.tool.summary.list_profiles";
            case "get_profile" -> "message.p2s.agent.tool.summary.get_profile";
            case "get_project_state" -> "message.p2s.agent.tool.summary.get_project_state";
            case "read_workspace_file" -> "message.p2s.agent.tool.summary.read_workspace_file";
            case "create_workspace_file" -> "message.p2s.agent.tool.summary.create_workspace_file";
            case "rename_workspace_file" -> "message.p2s.agent.tool.summary.rename_workspace_file";
            case "delete_workspace_file" -> "message.p2s.agent.tool.summary.delete_workspace_file";
            case "propose_patch" -> "message.p2s.agent.tool.summary.propose_patch";
            case "search_block_ids" -> "message.p2s.agent.tool.summary.search_block_ids";
            default -> "";
        };
    }

    private static String rawToolName(AgentMessage.ToolCall call) {
        if (call == null || call.name() == null) {
            return "unknown";
        }
        String name = call.name().trim();
        return name.isBlank() ? "unknown" : name;
    }

    private static String buildToolDetail(AgentMessage.ToolCall call, JsonObject payload) {
        List<String> lines = new ArrayList<>();
        lines.add(P2SI18n.tr("message.p2s.agent.tool.detail.name", rawToolName(call)).getString());
        appendToolArgumentDetails(lines, normalizeArgsObject(call == null ? null : call.arguments()));
        if (payload == null || !asBoolean(payload, "ok", true)) {
            String error = payload == null ? "Execution failed" : asString(payload, "error");
            if (error.isBlank()) {
                error = "Execution failed";
            }
            lines.add(P2SI18n.tr("message.p2s.agent.tool.detail.error", error).getString());
        }
        return String.join("\n", lines);
    }

    private static void appendToolArgumentDetails(List<String> lines, JsonObject args) {
        if (args == null || args.entrySet().isEmpty()) {
            return;
        }
        List<String> handledKeys = new ArrayList<>();
        String[] preferredKeys = {"path", "query", "name", "id", "action", "request_id", "profile_id", "instructions", "prompt", "content"};
        for (String key : preferredKeys) {
            if (args.has(key)) {
                appendToolArgumentLine(lines, args, key);
                handledKeys.add(key);
            }
        }
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || handledKeys.contains(key)) {
                continue;
            }
            appendToolArgumentLine(lines, args, key);
        }
    }

    private static void appendToolArgumentLine(List<String> lines, JsonObject args, String key) {
        if (args == null || key == null || key.isBlank() || !args.has(key)) {
            return;
        }
        String value = formatToolArgumentValue(args.get(key));
        if (value.isBlank()) {
            return;
        }
        lines.add(key + ": " + value);
    }

    private static String formatToolArgumentValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        String formatted;
        if (value.isJsonPrimitive()) {
            try {
                formatted = value.getAsString();
            } catch (Exception ignored) {
                formatted = value.toString();
            }
        } else {
            formatted = GSON.toJson(value);
        }
        String normalized = formatted.replace('\n', ' ').replace('\r', ' ').trim();
        int maxLength = 180;
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength - 3) + "...";
        }
        return normalized;
    }

    // ---------------------------------------------------------------- session persistence

    private static String sessionId(LocalSession session) {
        if (session == null || session.id == null || session.id.isBlank()) {
            return "default";
        }
        return session.id;
    }

    // Called on the client thread while LOCK is held. No worker may read the
    // singleton UI state to save a detached session.
    private static void saveSessionLocked(LocalSession session) {
        try {
            refreshSessionViewLocked(session);
            ClientSessionState.SessionViewSnapshot view = session.lastView;
            if (view == null) {
                return;
            }
            List<SessionPersistence.ChatMessageEntry> chatLog = new ArrayList<>();
            String title = "";
            for (ClientSessionState.ChatMessage message : view.messages()) {
                chatLog.add(new SessionPersistence.ChatMessageEntry(message.id(), message.role(),
                        message.text(), message.kind(), message.detail()));
                if (title.isBlank() && P2SI18n.isUserRole(message.role())) {
                    title = message.text().trim();
                }
            }
            if (title.length() > 60) {
                title = title.substring(0, 57) + "...";
            }
            if (title.isBlank()) {
                title = P2SI18n.tr("screen.p2s.sessions.default_title",
                        session.id.substring(0, Math.min(8, session.id.length()))).getString();
            }
            List<SessionPersistence.PlanItemEntry> plan = new ArrayList<>();
            for (ClientSessionState.PlanItem item : view.planItems()) {
                plan.add(new SessionPersistence.PlanItemEntry(item.step(), item.status()));
            }
            SessionPersistence.ChoiceRequestEntry choice = null;
            if (view.pendingChoice() != null) {
                List<SessionPersistence.ChoiceOptionEntry> options = new ArrayList<>();
                for (ClientSessionState.ChoiceOption option : view.pendingChoice().options()) {
                    options.add(new SessionPersistence.ChoiceOptionEntry(option.id(), option.label(), option.description()));
                }
                choice = new SessionPersistence.ChoiceRequestEntry(view.pendingChoice().requestId(),
                        view.pendingChoice().prompt(), List.copyOf(options));
            }
            session.selectedWorkspacePath = view.selectedWorkspacePath();
            // The persisted history keeps the previous wire format: system message first.
            List<JsonObject> history = OpenAiMessageCodec.toWire(systemPrompt(),
                    ContextBuilder.repair(session.agent.messages()));
            SessionPersistence.SavedSession saved = new SessionPersistence.SavedSession(
                    session.id, session.projectId, title, session.createdAt, System.currentTimeMillis(),
                    chatLog.size(), history, List.copyOf(chatLog), List.copyOf(plan),
                    view.planExplanation(), session.selectedWorkspacePath, choice, scriptStoreJson(session));
            // A single writer preserves capture order even when the same session
            // finishes, closes, and is restored in quick succession.
            PENDING_SAVES.put(saved.id(), saved);
            SAVE_EXECUTOR.execute(() -> {
                try {
                    SessionPersistence.saveSession(saved);
                } finally {
                    PENDING_SAVES.remove(saved.id(), saved);
                }
            });
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Auto-save session failed: {}", e.getMessage());
        }
    }

    private static JsonObject scriptStoreJson(LocalSession session) {
        JsonObject store = new JsonObject();
        session.scriptStore.forEach((key, value) -> store.add(key, value.deepCopy()));
        return store;
    }

    public static void onSessionViewChanged() {
        synchronized (LOCK) {
            if (currentSession != null) {
                refreshSessionViewLocked(currentSession);
            }
        }
    }

    private static void refreshSessionViewLocked(LocalSession session) {
        if (session != currentSession) {
            return;
        }
        ClientSessionState.SessionViewSnapshot view = ClientSessionState.captureSessionView();
        if (session.projectId.equals(view.projectId())) {
            session.lastView = view;
        }
    }

    public static void onClientJoin() {
        long epoch;
        synchronized (LOCK) {
            if (currentSession != null) {
                closeSessionLocked(currentSession);
                currentSession = null;
            }
            epoch = ++connectionEpoch;
            autoRestoreAttempted = false;
        }
        postToClient(() -> {
            synchronized (LOCK) {
                if (epoch != connectionEpoch) {
                    return;
                }
                if (!ClientServerBridge.hasRequiredServer()) {
                    clearLocalSessionView();
                    ClientServerBridge.notifyMissingServer();
                } else if (!autoRestoreAttempted) {
                    restoreLatestSession(null, false);
                }
            }
        });
    }

    public static void onClientDisconnect() {
        synchronized (LOCK) {
            if (currentSession != null) {
                closeSessionLocked(currentSession);
                currentSession = null;
            }
            connectionEpoch++;
            autoRestoreAttempted = false;
            ClientToolBridge.onDisconnect();
            clearLocalSessionView();
            ClientSessionState.resetAll();
        }
    }

    /** Save, fence and abort a session that is being replaced or detached. */
    private static void closeSessionLocked(LocalSession session) {
        saveSessionLocked(session);
        session.runs.close();
        if (session.runSignal != null) {
            session.runSignal.abort("Session closed");
        }
        session.agent.abort("Session closed");
        SubagentManager.cancelSession(session.id);
    }

    public static void restoreLatestSession(String projectId, boolean notifyOnFailure) {
        if (!ClientServerBridge.hasRequiredServer()) {
            return;
        }
        synchronized (LOCK) {
            autoRestoreAttempted = true;
            if (currentSession != null) {
                return;
            }
        }

        List<SessionPersistence.SessionIndexEntry> stored = (projectId == null || projectId.isBlank())
                ? SessionPersistence.listSessions()
                : SessionPersistence.listSessions(projectId);
        Map<String, SessionPersistence.SessionIndexEntry> latest = new LinkedHashMap<>();
        for (SessionPersistence.SessionIndexEntry entry : stored) {
            latest.put(entry.id(), entry);
        }
        for (SessionPersistence.SavedSession pending : PENDING_SAVES.values()) {
            if (projectId == null || projectId.isBlank() || projectId.equals(pending.projectId())) {
                latest.put(pending.id(), new SessionPersistence.SessionIndexEntry(pending.id(), pending.projectId(),
                        pending.title(), pending.createdAt(), pending.updatedAt(), pending.messageCount()));
            }
        }
        List<SessionPersistence.SessionIndexEntry> sessions = new ArrayList<>(latest.values());
        sessions.sort(Comparator.comparingLong(SessionPersistence.SessionIndexEntry::updatedAt).reversed());
        if (sessions.isEmpty()) {
            if (notifyOnFailure) {
                postToClient(() -> ClientSessionState.setStatus(""));
            }
            return;
        }
        SessionPersistence.SessionIndexEntry latestEntry = sessions.get(0);
        if (latestEntry == null || latestEntry.id() == null || latestEntry.id().isBlank()) {
            return;
        }
        restoreSession(latestEntry.id());
    }

    public static void maybeAutoApplyPendingPatch() {
        if (!P2SClientConfig.getAutoApplyPatch() || !ClientSessionState.hasAnyPendingPatch()) {
            return;
        }
        synchronized (LOCK) {
            if (currentSession == null || currentSession.runs.isRunning()) {
                return;
            }
        }
        submitPatchApplyAll();
    }

    public static void restoreSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (!ClientServerBridge.hasRequiredServer()) {
            ClientServerBridge.notifyMissingServer();
            return;
        }

        synchronized (LOCK) {
            if (currentSession != null && currentSession.runs.isRunning()) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Cannot restore session while agent is running.",
                        false,
                        "busy"
                ));
                return;
            }
        }

        SessionPersistence.SavedSession pending = PENDING_SAVES.get(sessionId);
        SessionPersistence.SavedSession saved = pending == null ? SessionPersistence.loadSession(sessionId) : pending;
        if (saved == null) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "Failed to load session.",
                    false,
                    "error"
            ));
            return;
        }

        closeCurrentSessionInternal(true);

        LocalSession session = new LocalSession();
        session.id = saved.id();
        session.runs = new AgentRunGuard(session.id, connectionEpoch);
        // The saved system message is replaced by the current prompt and contract.
        List<AgentMessage> transcript = OpenAiMessageCodec.fromWire(saved.llmHistory()).messages();
        session.agent = createAgent(session, transcript);
        saved.scriptStore().entrySet().forEach(entry -> session.scriptStore.put(entry.getKey(), entry.getValue().deepCopy()));
        session.createdAt = saved.createdAt();
        session.serverSessionStarted = false;
        session.projectId = saved.projectId() == null ? "" : saved.projectId();
        session.selectedWorkspacePath = saved.selectedWorkspacePath() == null ? "" : saved.selectedWorkspacePath();
        List<ClientSessionState.ChatMessage> savedMessages = new ArrayList<>();
        for (SessionPersistence.ChatMessageEntry message : saved.chatLog()) {
            savedMessages.add(new ClientSessionState.ChatMessage(message.id(), message.role(), message.text(), message.kind(), message.detail()));
        }
        List<ClientSessionState.PlanItem> savedPlan = new ArrayList<>();
        for (SessionPersistence.PlanItemEntry item : saved.planItems()) {
            savedPlan.add(new ClientSessionState.PlanItem(item.step(), item.status()));
        }
        ClientSessionState.ChoiceRequest savedChoice = null;
        if (saved.pendingChoice() != null) {
            List<ClientSessionState.ChoiceOption> options = new ArrayList<>();
            for (SessionPersistence.ChoiceOptionEntry option : saved.pendingChoice().options()) {
                options.add(new ClientSessionState.ChoiceOption(option.id(), option.label(), option.description()));
            }
            savedChoice = new ClientSessionState.ChoiceRequest(saved.pendingChoice().requestId(), saved.pendingChoice().prompt(), options);
        }
        session.lastView = new ClientSessionState.SessionViewSnapshot(session.projectId, session.selectedWorkspacePath,
                savedMessages, saved.planExplanation(), savedPlan, savedChoice);

        synchronized (LOCK) {
            currentSession = session;
        }

        postToClient(session, () -> {
            clearLocalSessionView();
            if (saved.chatLog() != null) {
                for (SessionPersistence.ChatMessageEntry entry : saved.chatLog()) {
                    ClientSessionState.addMessage(entry.id(), entry.role(), entry.text(), entry.kind(), entry.detail());
                }
            }
            if ((saved.planItems() != null && !saved.planItems().isEmpty())
                    || (saved.planExplanation() != null && !saved.planExplanation().isBlank())) {
                List<ClientSessionState.PlanItem> items = new ArrayList<>();
                if (saved.planItems() != null) {
                    for (SessionPersistence.PlanItemEntry entry : saved.planItems()) {
                        items.add(new ClientSessionState.PlanItem(entry.step(), entry.status()));
                    }
                }
                ClientSessionState.setPlan(saved.planExplanation(), items);
            }
            if (saved.pendingChoice() != null && saved.pendingChoice().options() != null && !saved.pendingChoice().options().isEmpty()) {
                List<ClientSessionState.ChoiceOption> options = new ArrayList<>();
                for (SessionPersistence.ChoiceOptionEntry entry : saved.pendingChoice().options()) {
                    if (entry == null) {
                        continue;
                    }
                    options.add(new ClientSessionState.ChoiceOption(entry.id(), entry.label(), entry.description()));
                }
                ClientSessionState.setPendingChoice(saved.pendingChoice().requestId(), saved.pendingChoice().prompt(), options);
            }
            ClientSessionState.setStatus("restored");
        });

        String payload = buildStartPayload(session);
        session.serverSessionStarted = ClientServerBridge.sendSessionAction("start", payload);
        if (!session.serverSessionStarted) {
            synchronized (LOCK) {
                if (currentSession == session) {
                    session.runs.close();
                    currentSession = null;
                    clearLocalSessionView();
                }
            }
        }
    }

    public static void newSession() {
        synchronized (LOCK) {
            if (currentSession != null && currentSession.runs.isRunning()) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Cannot start new session while agent is running.",
                        false,
                        "busy"
                ));
                return;
            }
        }
        closeCurrentSessionInternal(true);
    }

    public static void closeCurrentSession() {
        synchronized (LOCK) {
            if (currentSession != null && currentSession.runs.isRunning()) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Cannot close session while agent is running.",
                        false,
                        "busy"
                ));
                return;
            }
        }
        closeCurrentSessionInternal(true);
    }

    public static void onProjectChanged() {
        // The server may already have switched projects. Fence the previous run
        // rather than leaving it able to dispatch tools against the new project.
        closeCurrentSessionInternal(false);
        String projectId = ClientSessionState.getProjectId();
        if (projectId != null && !projectId.isBlank()) {
            restoreLatestSession(projectId, false);
        }
    }

    private static void closeCurrentSessionInternal(boolean clearStatus) {
        LocalSession previous;
        long epoch;
        synchronized (LOCK) {
            previous = currentSession;
            if (previous != null) {
                closeSessionLocked(previous);
            }
            currentSession = null;
            epoch = connectionEpoch;
        }
        if (previous != null && previous.serverSessionStarted) {
            ClientServerBridge.sendSessionAction("end", "");
        }
        postToClient(() -> {
            synchronized (LOCK) {
                if (currentSession == null && epoch == connectionEpoch) {
                    clearLocalSessionView();
                    if (clearStatus) {
                        ClientSessionState.setStatus("");
                    }
                }
            }
        });
    }

    private static void clearLocalSessionView() {
        ClientSessionState.clearMessages();
        ClientSessionState.clearPlan();
        ClientSessionState.clearPendingChoice();
        ClientSessionState.clearPendingPatch();
        ClientSessionState.endStreaming();
    }

    private static String buildStartPayload(LocalSession session) {
        JsonObject json = new JsonObject();
        if (session != null && session.id != null && !session.id.isBlank()) {
            json.addProperty("sessionId", session.id);
        }
        String projectId = session == null ? "" : session.projectId;
        if ((projectId == null || projectId.isBlank()) && ClientSessionState.hasProject()) {
            projectId = ClientSessionState.getProjectId();
        }
        if (projectId != null && !projectId.isBlank()) {
            json.addProperty("projectId", projectId);
        }
        String selectedWorkspacePath = session == null ? "" : session.selectedWorkspacePath;
        if ((selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) && ClientSessionState.isActive()) {
            selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        }
        if (selectedWorkspacePath != null && !selectedWorkspacePath.isBlank()) {
            json.addProperty("selectedWorkspacePath", selectedWorkspacePath);
        }
        return GSON.toJson(json);
    }

    private static LocalSession ensureSessionLocked() {
        if (!ClientServerBridge.hasRequiredServer()) {
            ClientServerBridge.notifyMissingServer();
            return null;
        }
        if (currentSession != null) {
            return currentSession;
        }
        String projectId = ClientSessionState.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "No project is open. Open or create a project first.",
                    false,
                    "error"
            ));
            return null;
        }

        LocalSession session = new LocalSession();
        String existingSessionId = ClientSessionState.isActive() ? ClientSessionState.getSessionId() : "";
        session.id = existingSessionId == null || existingSessionId.isBlank() ? UUID.randomUUID().toString() : existingSessionId;
        session.runs = new AgentRunGuard(session.id, connectionEpoch);
        session.agent = createAgent(session, List.of());
        session.createdAt = System.currentTimeMillis();
        session.projectId = projectId;
        session.selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();

        currentSession = session;
        refreshSessionViewLocked(session);
        String payload = buildStartPayload(session);
        session.serverSessionStarted = ClientServerBridge.sendSessionAction("start", payload);
        if (!session.serverSessionStarted) {
            session.runs.close();
            currentSession = null;
            return null;
        }
        return session;
    }

    // ---------------------------------------------------------------- run fencing and threads

    private static boolean isCurrentRunLocked(LocalSession session, RunIdentity run) {
        return session == currentSession && session.runs.isOpen(connectionEpoch) && session.runs.isCurrent(run);
    }

    private static boolean isCurrentRun(LocalSession session, RunIdentity run) {
        synchronized (LOCK) {
            return isCurrentRunLocked(session, run)
                    && session.projectId.equals(ClientSessionState.getProjectId());
        }
    }

    private static void postToClient(LocalSession session, RunIdentity run, Runnable action) {
        postToClient(session, run, action, true);
    }

    private static void postToClient(LocalSession session, RunIdentity run, Runnable action, boolean captureView) {
        postToClient(() -> {
            synchronized (LOCK) {
                if (isCurrentRun(session, run)) {
                    session.runs.runIfCurrent(run, action);
                    if (captureView) {
                        refreshSessionViewLocked(session);
                    }
                }
            }
        });
    }

    private static void postToClient(LocalSession session, Runnable action) {
        postToClient(() -> {
            synchronized (LOCK) {
                if (session == currentSession && session.runs.isOpen(connectionEpoch)) {
                    action.run();
                    refreshSessionViewLocked(session);
                }
            }
        });
    }

    private static <T> T callOnClient(LocalSession session, RunIdentity run, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        postToClient(() -> {
            synchronized (LOCK) {
                try {
                    if (!isCurrentRun(session, run)) {
                        throw new CancellationException("Agent run is no longer active");
                    }
                    T value = action.get();
                    refreshSessionViewLocked(session);
                    result.complete(value);
                } catch (Exception ex) {
                    result.completeExceptionally(ex);
                }
            }
        });
        return result.join();
    }

    private static void postToClient(Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || action == null) {
            return;
        }
        mc.execute(action);
    }

    private record PendingApproval(String path, String revision) {
        PendingApproval {
            path = path == null ? "" : path;
            revision = revision == null ? "" : revision;
        }
    }

    private static final class LocalSession {
        String id;
        AgentRunGuard runs;
        Agent agent;
        /** Fences harness work outside the agent loop (compaction, approval receipts). */
        AbortSignal runSignal;
        RunIdentity activeRun;
        PendingApproval pendingApproval;
        /** Player decisions on staged files, reported to the model once none are left pending. */
        final List<String> reviewDecisions = new ArrayList<>();
        /** Values scripts keep across calls (store/load); saved with the session. */
        final Map<String, JsonElement> scriptStore = new ConcurrentHashMap<>();
        ClientSessionState.SessionViewSnapshot lastView;
        boolean serverSessionStarted;
        long createdAt;
        String projectId = "";
        String selectedWorkspacePath = "";
    }
}
