package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.Agent;
import com.p2s.agent.core.AgentEvent;
import com.p2s.agent.core.AgentLoop;
import com.p2s.agent.core.AgentLoopConfig;
import com.p2s.agent.core.AgentMessage;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.llm.LlmRequestConfig;
import com.p2s.agent.llm.OpenAiChatCompletions;
import com.p2s.agent.llm.OpenAiMessageCodec;
import com.p2s.agent.tool.RegistryTools;
import com.p2s.agent.tool.ToolDefinition;
import com.p2s.agent.tool.ToolExecutors;
import com.p2s.agent.tool.ToolRegistry;
import com.p2s.agent.vfs.VfsException;
import com.p2s.store.SkillStore;
import com.p2s.store.SubagentProfileStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class SubagentManager {
    static final WorkspaceClaims CLAIMS = new WorkspaceClaims();
    private static final Gson GSON = new Gson();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int MAX_ACTIVE_SUBAGENTS = 2;
    private static final int MAX_QUEUED_SUBAGENTS = 16;
    private static final int MAX_RESPONSE_CHARS = 8192;
    private static final int MAX_SUMMARY_CHARS = 512;
    private static final int MAX_TRACE_ITEMS = 40;
    private static final int MAX_ATTEMPT_RECORDS = 20;
    private static final int MAX_PLAN_ITEMS = 40;
    private static final int MAX_TOOL_LOOPS = 30;
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            MAX_ACTIVE_SUBAGENTS,
            MAX_ACTIVE_SUBAGENTS,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_SUBAGENTS),
            r -> {
                Thread t = new Thread(r, "p2s-subagent");
                t.setDaemon(true);
                return t;
            }
    );

    private static final ConcurrentHashMap<String, SubagentTask> TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> SESSION_INDEX = new ConcurrentHashMap<>();

    private SubagentManager() {
    }

    public static JsonObject createSubagent(String sessionId, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        String taskText = asString(args, "task");
        if (taskText.isBlank()) {
            return error("create_subagent", "Missing task");
        }

        String requestedProfileId = asString(args, "profile_id");
        SubagentProfileStore.SubagentProfile profile = SubagentProfileStore.resolveProfile(requestedProfileId);
        if (profile == null) {
            return error("create_subagent", "Profile not found");
        }
        if (!profile.enabled()) {
            return error("create_subagent", "Profile disabled: " + profile.id());
        }
        if (profile.allowedTools() == null || profile.allowedTools().isEmpty()) {
            return error("create_subagent", "Profile has no allowed tools: " + profile.id());
        }

        boolean hasExplicitSkillIds = args.has("skill_ids");
        List<String> requestedSkillIds = parseStringArray(args.get("skill_ids"));
        ResolveSkillResult resolveSkills = resolveSkillIds(requestedSkillIds, hasExplicitSkillIds);
        if (hasExplicitSkillIds && !requestedSkillIds.isEmpty() && resolveSkills.skillIds().isEmpty()) {
            if (resolveSkills.warnings().isEmpty()) {
                return error("create_subagent", "No valid requested skills resolved");
            }
            return error("create_subagent", "No valid requested skills resolved: " + String.join("; ", resolveSkills.warnings()));
        }
        String id = Long.toString(NEXT_ID.incrementAndGet(), 36);
        String sid = sessionKey(sessionId);
        long now = System.currentTimeMillis();
        JsonObject metadata = args.has("metadata") && args.get("metadata").isJsonObject()
                ? args.getAsJsonObject("metadata").deepCopy()
                : null;

        SubagentTask subagent = new SubagentTask();
        subagent.id = id;
        subagent.sessionId = sid;
        subagent.profileId = profile.id();
        subagent.profileName = profile.name();
        subagent.task = taskText;
        subagent.skillIds = new ArrayList<>(resolveSkills.skillIds());
        subagent.metadata = metadata;
        subagent.status = SubagentStatus.QUEUED;
        subagent.createdAt = now;
        subagent.summary = "Queued";
        subagent.attempt = 1;
        subagent.attemptCount = 1;
        subagent.projectId = ClientSessionState.getProjectId();
        try {
            subagent.ownedPaths = WorkspaceClaims.parse(args.get("owned_paths"));
            prepareAttempt(subagent, profile);
        } catch (VfsException e) {
            return error("create_subagent", e.getMessage());
        }
        initializeTaskHistory(subagent, profile);

        TASKS.put(id, subagent);
        SESSION_INDEX.computeIfAbsent(sid, k -> new CopyOnWriteArrayList<>()).add(id);

        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] Subagent created -> id={}, session={}, profile={}, task={}, allowedTools={}, skillIds={}",
                    id, sid, profile.id(), taskText, profile.allowedTools(), resolveSkills.skillIds());
        }

        queueSubagentRun(subagent, profile);

        JsonObject payload = ok("create_subagent");
        payload.addProperty("subagent_id", id);
        addCapabilities(payload, subagent);
        payload.addProperty("status", statusName(subagent.status));
        payload.addProperty("profile_id", profile.id());
        payload.addProperty("created_at", now);
        payload.addProperty("queued", subagent.status == SubagentStatus.QUEUED);
        payload.addProperty("attempt", subagent.attempt);
        payload.addProperty("attempt_count", subagent.attemptCount);
        payload.addProperty("skill_count", resolveSkills.skillIds().size());
        payload.add("skill_ids", toJsonArray(resolveSkills.skillIds()));
        if (!resolveSkills.warnings().isEmpty()) {
            payload.add("warnings", toJsonArray(resolveSkills.warnings()));
        }
        return payload;
    }

    public static JsonObject continueSubagent(String sessionId, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        String subagentId = asString(args, "id");
        if (subagentId.isBlank()) {
            return error("continue_subagent", "Missing id");
        }
        SubagentTask task = getBySession(sessionId, subagentId);
        if (task == null) {
            return error("continue_subagent", "Subagent not found");
        }
        SubagentProfileStore.SubagentProfile profile = SubagentProfileStore.getProfile(task.profileId);
        if (profile == null) {
            return error("continue_subagent", "Profile not found: " + task.profileId);
        }
        if (!profile.enabled()) {
            return error("continue_subagent", "Profile disabled: " + profile.id());
        }
        if (profile.allowedTools() == null || profile.allowedTools().isEmpty()) {
            return error("continue_subagent", "Profile has no allowed tools: " + profile.id());
        }

        String instruction = asString(args, "instruction");
        if (instruction.isBlank()) {
            instruction = "Continue from previous context and finish the task with actionable output.";
        }

        int attempt;
        int attemptCount;
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED) {
                return error("continue_subagent", "Subagent is deleted");
            }
            if (task.status == SubagentStatus.RUNNING || task.status == SubagentStatus.QUEUED) {
                return error("continue_subagent", "Subagent is already active");
            }
            if (!canContinue(task.status)) {
                return error("continue_subagent", "Subagent cannot continue from status: " + statusName(task.status));
            }
            try {
                prepareAttempt(task, profile);
            } catch (VfsException e) {
                return error("continue_subagent", e.getMessage());
            }
            if (task.history == null || task.history.isEmpty()) {
                initializeTaskHistory(task, profile);
            }
            JsonObject continueMsg = new JsonObject();
            continueMsg.addProperty("role", "user");
            continueMsg.addProperty("content", instruction);
            task.history.add(continueMsg);
            task.cancelRequested = false;
            task.status = SubagentStatus.QUEUED;
            task.summary = "Queued (continue)";
            task.error = "";
            task.response = "";
            task.startedAt = 0L;
            task.endedAt = 0L;
            task.attempt += 1;
            task.attemptCount += 1;
            attempt = task.attempt;
            attemptCount = task.attemptCount;
        }

        queueSubagentRun(task, profile);
        TaskSnapshot snap = snapshot(task);

        JsonObject payload = ok("continue_subagent");
        payload.addProperty("id", subagentId);
        payload.addProperty("status", statusName(snap.status));
        addCapabilities(payload, task);
        payload.addProperty("continued", true);
        payload.addProperty("queued", snap.status == SubagentStatus.QUEUED);
        payload.addProperty("attempt", attempt);
        payload.addProperty("attempt_count", attemptCount);
        return payload;
    }

    public static void cancelSession(String sessionId) {
        String sid = sessionKey(sessionId);
        for (SubagentTask task : TASKS.values()) {
            synchronized (task) {
                if (sid.equals(task.sessionId)
                        && (task.status == SubagentStatus.RUNNING || task.status == SubagentStatus.QUEUED)) {
                    task.cancelRequested = true;
                    CLAIMS.release(task.projectId, task.id, task.ownedPaths);
                    if (task.agent != null) {
                        task.agent.abort("Parent session cancelled");
                    }
                }
            }
        }
    }

    private static boolean isTaskActive(SubagentTask task) {
        synchronized (task) {
            return !task.cancelRequested && task.status == SubagentStatus.RUNNING;
        }
    }

    public static JsonObject listSubagents(String sessionId, boolean includeDeleted) {
        String sid = sessionKey(sessionId);
        List<String> ids = SESSION_INDEX.getOrDefault(sid, new CopyOnWriteArrayList<>());
        JsonArray items = new JsonArray();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            SubagentTask task = TASKS.get(id);
            if (task == null) {
                continue;
            }
            TaskSnapshot snapshot = snapshot(task);
            if (!includeDeleted && snapshot.status == SubagentStatus.DELETED) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", snapshot.id);
            item.addProperty("status", statusName(snapshot.status));
            item.addProperty("profile_id", snapshot.profileId);
            item.addProperty("created_at", snapshot.createdAt);
            item.addProperty("started_at", snapshot.startedAt);
            item.addProperty("ended_at", snapshot.endedAt);
            item.addProperty("attempt", snapshot.attempt);
            item.addProperty("attempt_count", snapshot.attemptCount);
            item.addProperty("can_continue", canContinue(snapshot.status));
            item.addProperty("summary", snapshot.summary);
            if (!snapshot.error.isBlank()) {
                item.addProperty("error", snapshot.error);
            }
            addCapabilities(item, task);
            items.add(item);
        }
        JsonObject payload = ok("list_subagents");
        payload.addProperty("count", items.size());
        payload.add("subagents", items);
        return payload;
    }

    public static JsonObject getSubagent(String sessionId, String subagentId) {
        SubagentTask task = getBySession(sessionId, subagentId);
        if (task == null) {
            return error("get_subagent", "Subagent not found");
        }
        TaskSnapshot snapshot = snapshot(task);
        JsonObject payload = ok("get_subagent");
        payload.addProperty("id", snapshot.id);
        payload.addProperty("status", statusName(snapshot.status));
        payload.addProperty("profile_id", snapshot.profileId);
        payload.addProperty("profile_name", snapshot.profileName);
        payload.addProperty("task", snapshot.task);
        addCapabilities(payload, task);
        payload.add("skill_ids", toJsonArray(snapshot.skillIds));
        payload.addProperty("created_at", snapshot.createdAt);
        payload.addProperty("started_at", snapshot.startedAt);
        payload.addProperty("ended_at", snapshot.endedAt);
        payload.addProperty("attempt", snapshot.attempt);
        payload.addProperty("attempt_count", snapshot.attemptCount);
        payload.addProperty("can_continue", canContinue(snapshot.status));
        payload.addProperty("summary", snapshot.summary);
        payload.addProperty("response", snapshot.response);
        if (!snapshot.error.isBlank()) {
            payload.addProperty("error", snapshot.error);
        }
        if (snapshot.metadata != null) {
            payload.add("metadata", snapshot.metadata.deepCopy());
        }
        payload.add("tool_trace", toJsonArray(snapshot.toolTrace));
        if (snapshot.attempts != null && !snapshot.attempts.isEmpty()) {
            JsonArray attempts = new JsonArray();
            for (AttemptRecord record : snapshot.attempts) {
                JsonObject item = new JsonObject();
                item.addProperty("attempt", record.attempt());
                item.addProperty("status", statusName(record.status()));
                item.addProperty("summary", record.summary());
                item.addProperty("response", record.response());
                item.addProperty("error", record.error());
                item.addProperty("started_at", record.startedAt());
                item.addProperty("ended_at", record.endedAt());
                attempts.add(item);
            }
            payload.add("attempts", attempts);
        }
        return payload;
    }

    static JsonObject waitForSubagent(String sessionId, String id, int timeoutMs, AbortSignal signal) {
        SubagentTask task = getBySession(sessionId, id);
        if (task == null) return error("task_status", "Subagent not found");
        try {
            synchronized (task) {
                boolean finished = awaitCompletion(task,
                        () -> task.status == SubagentStatus.QUEUED || task.status == SubagentStatus.RUNNING,
                        timeoutMs, signal);
                JsonObject result = getSubagent(sessionId, id);
                result.addProperty("timed_out", !finished);
                return result;
            }
        } catch (AbortSignal.AbortedException ex) {
            return error("task_status", "Stopped waiting");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return error("task_status", "Interrupted while waiting");
        }
    }

    static boolean awaitCompletion(Object monitor, java.util.function.BooleanSupplier active, int timeoutMs,
                                   AbortSignal signal) throws InterruptedException, AbortSignal.AbortedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(clamp(timeoutMs, 1, 30_000));
        try (var registration = signal.onAbort(() -> {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        })) {
            synchronized (monitor) {
                while (active.getAsBoolean()) {
                    signal.throwIfAborted();
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                }
                signal.throwIfAborted();
                return true;
            }
        }
    }

    private static void addCapabilities(JsonObject result, SubagentTask task) {
        synchronized (task) {
            result.add("effective_tools", toJsonArray(task.effectiveTools));
            result.add("owned_paths", toJsonArray(task.ownedPaths));
        }
    }

    private static void prepareAttempt(SubagentTask task, SubagentProfileStore.SubagentProfile profile) throws VfsException {
        if (!task.projectId.equals(ClientSessionState.getProjectId())) throw new VfsException("Task belongs to another project");
        task.v2 = P2SClientConfig.isToolsetV2();
        Set<String> allowed = new LinkedHashSet<>(profile.allowedTools());
        var bundle = task.v2 ? AgentToolsV2.create(v2Host(task), v2AllowedTools(allowed)) : null;
        ToolRegistry registry = bundle == null ? createToolRegistry(task, allowed) : bundle.registry();
        List<AgentTool> tools = bundle == null ? RegistryTools.from(registry, () -> isTaskActive(task), null)
                : bundle.agentTools(() -> isTaskActive(task), null);
        if (tools.isEmpty()) throw new VfsException("Profile has no available tools in this toolset");
        if (!task.ownedPaths.isEmpty() && tools.stream().noneMatch(tool ->
                registry.definition(tool.name()).effects().contains(ToolDefinition.Effect.PROJECT_STATE))) {
            throw new VfsException("Profile " + profile.id() + " is read-only; choose a writable profile for owned_paths");
        }
        CLAIMS.reserve(task.projectId, task.id, task.ownedPaths);
        task.tools = tools;
        task.effectiveTools = tools.stream().map(AgentTool::name).toList();
    }

    /** Caller holds task's monitor. Reservations end before a terminal state can be observed. */
    private static void finishStatus(SubagentTask task, SubagentStatus status) {
        task.status = status;
        task.tools = List.of();
        CLAIMS.release(task.projectId, task.id, task.ownedPaths);
        task.notifyAll();
    }

    public static JsonObject deleteSubagent(String sessionId, String subagentId) {
        SubagentTask task = getBySession(sessionId, subagentId);
        if (task == null) {
            return error("delete_subagent", "Subagent not found");
        }

        boolean alreadyDeleted;
        synchronized (task) {
            alreadyDeleted = task.status == SubagentStatus.DELETED;
            if (!alreadyDeleted) {
                task.cancelRequested = true;
                if (task.agent != null) {
                    task.agent.abort("Deleted by parent agent");
                }
                finishStatus(task, SubagentStatus.DELETED);
                task.summary = "Deleted";
                if (task.error == null || task.error.isBlank()) {
                    task.error = "Deleted by parent agent";
                }
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
            }
        }

        JsonObject payload = ok("delete_subagent");
        payload.addProperty("id", subagentId == null ? "" : subagentId);
        payload.addProperty("status", "deleted");
        payload.addProperty("already_deleted", alreadyDeleted);
        return payload;
    }

    public static JsonObject listProfiles() {
        List<SubagentProfileStore.ProfileSummary> profiles = SubagentProfileStore.listProfileSummaries();
        JsonArray items = new JsonArray();
        for (SubagentProfileStore.ProfileSummary profile : profiles) {
            JsonObject item = new JsonObject();
            item.addProperty("id", profile.id());
            item.addProperty("name", profile.name());
            item.addProperty("description", profile.description());
            item.addProperty("enabled", profile.enabled());
            item.addProperty("tool_count", profile.toolCount());
            items.add(item);
        }
        JsonObject payload = ok("list_profiles");
        payload.addProperty("count", items.size());
        payload.add("profiles", items);
        payload.addProperty("profile_root", "config/p2s_skills/.agent");
        return payload;
    }

    public static JsonObject getProfile(String profileId) {
        SubagentProfileStore.SubagentProfile profile = SubagentProfileStore.getProfile(profileId);
        if (profile == null) {
            return error("get_profile", "Profile not found: " + (profileId == null ? "" : profileId));
        }

        JsonObject payload = ok("get_profile");
        payload.addProperty("id", profile.id());
        payload.addProperty("name", profile.name());
        payload.addProperty("description", profile.description());
        payload.addProperty("system_prompt", profile.systemPrompt());
        payload.add("allowed_tools", toJsonArray(profile.allowedTools()));
        payload.add("effective_tools", toJsonArray(List.copyOf(v2AllowedTools(new LinkedHashSet<>(profile.allowedTools())))));
        payload.addProperty("max_loops", profile.maxLoops());
        payload.addProperty("timeout_seconds", profile.timeoutSeconds());
        payload.addProperty("enabled", profile.enabled());
        return payload;
    }

    private static void queueSubagentRun(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        if (task == null || profile == null) {
            return;
        }
        try {
            EXECUTOR.execute(() -> {
                try {
                    runSubagent(task, profile);
                } catch (RuntimeException e) {
                    fail(task, formatError(e));
                    notifyParent(task);
                }
            });
        } catch (RejectedExecutionException e) {
            long now = System.currentTimeMillis();
            synchronized (task) {
                finishStatus(task, SubagentStatus.FAILED);
                task.error = "Subagent queue is full";
                task.summary = "Queue full";
                task.response = "";
                task.startedAt = now;
                task.endedAt = now;
                appendAttemptRecord(task);
            }
        }
    }

    private static void initializeTaskHistory(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        if (task == null || profile == null) {
            return;
        }
        if (task.history == null) {
            task.history = new ArrayList<>();
        } else {
            task.history.clear();
        }

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", buildSubagentSystemPrompt(task, profile));
        task.history.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", task.task == null ? "" : task.task);
        task.history.add(user);

        if (task.metadata != null && !task.metadata.entrySet().isEmpty()) {
            JsonObject metadataMsg = new JsonObject();
            metadataMsg.addProperty("role", "user");
            metadataMsg.addProperty("content", "metadata: " + GSON.toJson(task.metadata));
            task.history.add(metadataMsg);
        }
    }

    private static void runSubagent(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        List<JsonObject> history;
        synchronized (task) {
            if (task.cancelRequested || task.status == SubagentStatus.DELETED) {
                finishStatus(task, SubagentStatus.DELETED);
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
                return;
            }
            if (task.history == null || task.history.isEmpty()) {
                initializeTaskHistory(task, profile);
            }
            task.status = SubagentStatus.RUNNING;
            task.startedAt = System.currentTimeMillis();
            task.summary = "Running (attempt " + task.attempt + ")";
            task.error = "";
            task.response = "";
            history = deepCopyMessages(task.history);
        }

        int maxLoops = clamp(profile.maxLoops(), 1, MAX_TOOL_LOOPS);
        int timeoutSeconds = clamp(profile.timeoutSeconds(), 5, 300);
        OpenAiMessageCodec.Decoded decoded = OpenAiMessageCodec.fromWire(history);

        // Same core loop as the main agent, restricted by the profile's tools, turns and timeout.
        ClientContextWindow window = ClientContextWindow.forClient(new OpenAiChatCompletions(() -> {
                    LlmRequestConfig base = P2SClientConfig.llmRequestConfig();
                    return base.withTimeoutSeconds(timeoutSeconds).withStreaming(false);
                }), (trigger, summary) -> P2SMod.LOGGER.info("Compacted subagent {} context", task.id),
                error -> P2SMod.LOGGER.warn("Subagent {} compaction failed: {}", task.id, error));
        window.restore(task.checkpoint.firstKept(), task.checkpoint.summary(), decoded.messages());
        AgentLoopConfig config = AgentLoopConfig.builder(window)
                .shouldStopAfterTurn(turn -> turn.turnIndex() + 1 >= maxLoops)
                .toolExecutor(ToolExecutors.shared())
                .build();
        Agent agent = new Agent(config, decoded.systemPrompt(), task.tools, decoded.messages());
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.ToolExecutionEnd end) {
                appendTrace(task, end.toolCall().name(), end.result().content());
                if (P2SMod.DEBUG) {
                    P2SMod.LOGGER.info("[DEBUG] Subagent {} tool: name={}, args={}, result={}", task.id,
                            end.toolCall().name(), end.toolCall().rawArguments(), GSON.toJson(end.result().content()));
                }
            }
        });
        synchronized (task) {
            task.agent = agent;
            if (task.cancelRequested) {
                agent.abort("Cancelled before start");
            }
        }

        AgentLoop.Outcome outcome;
        try {
            outcome = agent.continueRun();
        } catch (RuntimeException e) {
            synchronized (task) {
                task.agent = null;
            }
            if (!shouldStop(task)) {
                fail(task, formatError(e));
                notifyParent(task);
            }
            return;
        }
        synchronized (task) {
            task.agent = null;
            task.history = new ArrayList<>(OpenAiMessageCodec.toWire(decoded.systemPrompt(), agent.messages()));
            task.checkpoint = window.checkpoint();
        }
        if (shouldStop(task)) {
            return;
        }
        switch (outcome.reason()) {
            case COMPLETED, PAUSED -> {
                String text = lastAssistantText(outcome.newMessages());
                if (P2SMod.DEBUG) {
                    P2SMod.LOGGER.info("[DEBUG] Subagent {} completed -> durationMs={}, responseLen={}", task.id,
                            System.currentTimeMillis() - task.startedAt, text.length());
                }
                complete(task, text);
            }
            case STOPPED_BY_POLICY -> {
                fail(task, "Reached max subagent loops (" + maxLoops + ")");
                P2SMod.LOGGER.warn("Subagent {} reached max loops ({})", task.id, maxLoops);
            }
            case ERROR -> fail(task, agent.errorMessage().isBlank() ? "Request failed" : agent.errorMessage());
            case ABORTED -> fail(task, "Cancelled");
        }
        notifyParent(task);
    }

    /** Profiles list legacy tool names; v2 grants the tools that replace them, plus any v2 names given. */
    static Set<String> v2AllowedTools(Set<String> allowed) {
        Set<String> tools = new LinkedHashSet<>();
        for (String name : allowed) {
            tools.addAll(com.p2s.agent.tool.ToolCatalogV2.fromLegacy(name));
        }
        tools.removeAll(Set.of("ask_user", "task", "task_cancel"));
        tools.removeIf(name -> com.p2s.agent.tool.ToolCatalogV2.find(name) == null);
        return tools;
    }

    private static AgentToolsV2.Host v2Host(SubagentTask task) {
        return new AgentToolsV2.Host() {
            @Override
            public JsonObject server(String tool, JsonObject args) {
                return callServerTool(task, tool, args);
            }

            @Override
            public JsonObject onClient(java.util.function.Supplier<JsonObject> action) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                return mc == null ? action.get() : mc.submit(action).join();
            }

            @Override
            public String sessionId() {
                synchronized (task) {
                    return task.sessionId;
                }
            }

            @Override
            public Set<String> skillScope() {
                return scopedSkillSet(task);
            }

            @Override
            public java.util.Map<String, JsonElement> scriptStore() {
                return task.scriptStore;
            }

            @Override
            public boolean isSubagent() {
                return true;
            }
        };
    }

    private static String lastAssistantText(List<AgentMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AgentMessage.Assistant assistant && !assistant.failed()) {
                return assistant.text();
            }
        }
        return "";
    }

    /** Tell the parent session that this subagent reached a terminal state. */
    private static void notifyParent(SubagentTask task) {
        TaskSnapshot snapshot = snapshot(task);
        if (snapshot.status == SubagentStatus.DELETED || snapshot.status == SubagentStatus.CANCELLED) {
            return;
        }
        String sessionId;
        synchronized (task) {
            sessionId = task.sessionId;
        }
        String notice = "[subagent_notice] Subagent " + snapshot.id + " (profile " + snapshot.profileId + ") finished with status "
                + statusName(snapshot.status) + ". Summary: " + truncateText(
                snapshot.status == SubagentStatus.FAILED && !snapshot.error.isBlank() ? snapshot.error : snapshot.summary,
                MAX_SUMMARY_CHARS)
                + " Call " + (task.v2 ? "task_status" : "get_subagent") + " with id \"" + snapshot.id + "\" for the full response.";
        ClientAgentManager.onSubagentFinished(sessionId, snapshot.id, notice);
    }

    private static ToolRegistry createToolRegistry(SubagentTask task, Set<String> allowedTools) {
        return ToolRegistry.builder()
                .allowOnly(allowedTools)
                .register("list_skills", call -> listSkillsPayload(task))
                .register("read_skill", call -> readSkillPayload(task, call.arguments()))
                .register("read_subdoc", call -> readSubdocPayload(task, call.arguments()))
                .register("search_skill", call -> searchSkillPayload(task, call.arguments()))
                .register("update_plan", call -> updatePlanPayload(call.arguments()))
                .register("get_project_state", call -> callServerTool(task, call.name(), normalizeArgsObject(call.arguments())))
                .register("read_workspace_file", call -> callServerTool(task, call.name(), normalizeArgsObject(call.arguments())))
                .register("create_workspace_file", call -> callServerTool(task, call.name(), normalizeArgsObject(call.arguments())))
                .register("rename_workspace_file", call -> callServerTool(task, call.name(), normalizeArgsObject(call.arguments())))
                .register("delete_workspace_file", call -> callServerTool(task, call.name(), normalizeArgsObject(call.arguments())))
                .register("propose_patch", call -> callServerTool(task, call.name(), normalizeArgsObject(call.arguments())))
                .register("search_block_ids", call -> callServerTool(task, call.name(), normalizeArgsObject(call.arguments())))
                .build();
    }

    private static JsonObject updatePlanPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        if (!args.has("plan") || !args.get("plan").isJsonArray()) {
            return toolError("update_plan", "Missing plan array");
        }

        List<ClientSessionState.PlanItem> items = new ArrayList<>();
        for (JsonElement element : args.getAsJsonArray("plan")) {
            if (items.size() >= MAX_PLAN_ITEMS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String step = asString(obj, "step");
            if (step.isBlank()) {
                continue;
            }
            items.add(new ClientSessionState.PlanItem(step.trim(), normalizePlanStatus(asString(obj, "status"))));
        }

        ClientSessionState.setPlan(asString(args, "explanation"), items);
        JsonObject payload = toolOk("update_plan");
        payload.addProperty("count", items.size());
        return payload;
    }

    private static JsonObject listSkillsPayload(SubagentTask task) {
        JsonObject payload = toolOk("list_skills");
        String active = SkillStore.activeSkillId();
        payload.addProperty("active_skill_id", active == null ? "" : active);
        JsonArray items = new JsonArray();

        Set<String> scope = scopedSkillSet(task);
        for (SkillStore.SkillMeta meta : SkillStore.listSkills()) {
            if (!scope.isEmpty() && !scope.contains(meta.id())) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", meta.id());
            item.addProperty("name", meta.name());
            item.addProperty("description", meta.description());
            items.add(item);
        }

        payload.add("skills", items);
        payload.addProperty("count", items.size());
        payload.addProperty("scoped", !scope.isEmpty());
        return payload;
    }

    private static JsonObject readSkillPayload(SubagentTask task, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        Set<String> scope = scopedSkillSet(task);

        if (id.isBlank()) {
            if (!task.skillIds.isEmpty()) {
                id = task.skillIds.get(0);
            } else {
                id = SkillStore.activeSkillId();
            }
        }
        if (id.isBlank()) {
            return toolError("read_skill", "No skill id available");
        }
        if (!scope.isEmpty() && !scope.contains(id)) {
            return toolError("read_skill", "Skill not allowed for this subagent: " + id);
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

    private static JsonObject readSubdocPayload(SubagentTask task, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String path = asString(args, "path");
        Set<String> scope = scopedSkillSet(task);

        if (id.isBlank()) {
            if (!task.skillIds.isEmpty()) {
                id = task.skillIds.get(0);
            } else {
                id = SkillStore.activeSkillId();
            }
        }
        if (id.isBlank()) {
            return toolError("read_subdoc", "No skill id available");
        }
        if (!scope.isEmpty() && !scope.contains(id)) {
            return toolError("read_subdoc", "Skill not allowed for this subagent: " + id);
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

    private static JsonObject searchSkillPayload(SubagentTask task, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String query = asString(args, "query");
        int limit = asInt(args, "limit", 10, 1, 50);
        if (query.isBlank()) {
            return toolError("search_skill", "Missing query");
        }

        Set<String> scope = scopedSkillSet(task);
        List<SkillStore.SearchHit> hits = new ArrayList<>();
        if (!id.isBlank()) {
            if (!scope.isEmpty() && !scope.contains(id)) {
                return toolError("search_skill", "Skill not allowed for this subagent: " + id);
            }
            hits.addAll(SkillStore.searchSkill(id, query, limit));
        } else if (!scope.isEmpty()) {
            int remaining = limit;
            for (String skillId : scope) {
                if (remaining <= 0) {
                    break;
                }
                List<SkillStore.SearchHit> partial = SkillStore.searchSkill(skillId, query, remaining);
                hits.addAll(partial);
                remaining = limit - hits.size();
            }
        } else {
            hits.addAll(SkillStore.searchSkill("", query, limit));
        }

        if (hits.size() > limit) {
            hits = hits.subList(0, limit);
        }

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

    private static JsonObject callServerTool(SubagentTask task, String toolName, JsonObject args) {
        try {
            JsonObject payload = ClientToolBridge.callForAgent(toolName, args, () -> isTaskActive(task) && task.projectId.equals(ClientSessionState.getProjectId()), task.id).join();
            if (payload == null) {
                return toolError(toolName, "Empty server response");
            }
            return payload;
        } catch (Exception e) {
            return toolError(toolName, "Server tool failed: " + formatError(e));
        }
    }

    private static String buildSubagentSystemPrompt(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(P2SClientConfig.getSystemPrompt()).append('\n').append('\n');
        sb.append("## Subagent Contract").append('\n');
        sb.append("- You are a specialized subagent created by a parent agent.").append('\n');
        sb.append("- Focus only on the assigned task and return concise, actionable output.").append('\n');
        sb.append("- Do not ask to create, list, or delete subagents.").append('\n');
        sb.append("- Use only available tools and prefer minimal tool calls.").append('\n');
        sb.append("- If insufficient context exists, explain precisely what is missing.").append('\n');
        if (task.v2) {
            sb.append("- Files: workspace/ (project files, edits are staged for the player's review), skills/ and agents/")
                    .append(" (read only). Use ls/read/grep/find to look, edit/write/set_part/sh/script to change.").append('\n');
            sb.append("- Skills may mention propose_patch or read_skill; use the file tools instead.").append('\n');
        }
        sb.append('\n');
        if (!task.ownedPaths.isEmpty()) {
            sb.append("Assigned workspace files: ").append(String.join(", ", task.ownedPaths)).append('\n');
        }
        sb.append("Profile: ").append(profile.id()).append(" / ").append(profile.name()).append('\n');
        if (profile.systemPrompt() != null && !profile.systemPrompt().isBlank()) {
            sb.append(profile.systemPrompt().trim()).append('\n');
        }
        if (task.skillIds != null && !task.skillIds.isEmpty()) {
            sb.append("Scoped skills: ").append(String.join(", ", task.skillIds)).append('\n');
        }
        return sb.toString();
    }

    private static ResolveSkillResult resolveSkillIds(List<String> requested, boolean hasExplicitSkillIds) {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (SkillStore.SkillMeta meta : SkillStore.listSkills()) {
            available.add(meta.id());
        }

        if (!hasExplicitSkillIds || requested.isEmpty()) {
            return new ResolveSkillResult(List.of(), List.of());
        }

        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        String unknownPrefix = "Unknown requested skill id: ";

        for (String id : requested) {
            if (available.contains(id)) {
                chosen.add(id);
            } else {
                warnings.add(unknownPrefix + id);
            }
        }
        return new ResolveSkillResult(new ArrayList<>(chosen), warnings);
    }

    private static Set<String> scopedSkillSet(SubagentTask task) {
        if (task == null || task.skillIds == null || task.skillIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(task.skillIds);
    }

    private static SubagentTask getBySession(String sessionId, String subagentId) {
        if (subagentId == null || subagentId.isBlank()) {
            return null;
        }
        SubagentTask task = TASKS.get(subagentId);
        if (task == null) {
            return null;
        }
        String sid = sessionKey(sessionId);
        synchronized (task) {
            if (!sid.equals(task.sessionId)) {
                return null;
            }
        }
        return task;
    }

    private static TaskSnapshot snapshot(SubagentTask task) {
        synchronized (task) {
            return new TaskSnapshot(
                    task.id,
                    task.profileId,
                    task.profileName,
                    task.task,
                    task.status,
                    task.summary == null ? "" : task.summary,
                    task.response == null ? "" : task.response,
                    task.error == null ? "" : task.error,
                    new ArrayList<>(task.skillIds),
                    task.metadata == null ? null : task.metadata.deepCopy(),
                    new ArrayList<>(task.toolTrace),
                    task.attempt,
                    task.attemptCount,
                    new ArrayList<>(task.attempts),
                    task.createdAt,
                    task.startedAt,
                    task.endedAt
            );
        }
    }

    private static boolean shouldStop(SubagentTask task) {
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED || task.cancelRequested) {
                if (task.status != SubagentStatus.DELETED) {
                    finishStatus(task, SubagentStatus.CANCELLED);
                }
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
                if (task.status == SubagentStatus.CANCELLED) {
                    task.summary = "Cancelled";
                    appendAttemptRecord(task);
                }
                return true;
            }
            return false;
        }
    }

    private static void complete(SubagentTask task, String response) {
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED || task.cancelRequested) {
                if (task.status != SubagentStatus.DELETED) {
                    finishStatus(task, SubagentStatus.CANCELLED);
                }
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
                return;
            }
            finishStatus(task, SubagentStatus.COMPLETED);
            task.response = truncateText(response, MAX_RESPONSE_CHARS);
            task.summary = summarize(task.response);
            task.endedAt = System.currentTimeMillis();
            appendAttemptRecord(task);
        }
    }

    private static void fail(SubagentTask task, String message) {
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED) {
                return;
            }
            if (task.cancelRequested) {
                finishStatus(task, SubagentStatus.CANCELLED);
                task.summary = "Cancelled";
            } else {
                finishStatus(task, SubagentStatus.FAILED);
                task.summary = "Failed";
                task.error = truncateText(message, MAX_RESPONSE_CHARS);
            }
            if (task.endedAt <= 0) {
                task.endedAt = System.currentTimeMillis();
            }
            appendAttemptRecord(task);
        }
    }

    private static boolean canContinue(SubagentStatus status) {
        return status == SubagentStatus.FAILED
                || status == SubagentStatus.COMPLETED
                || status == SubagentStatus.CANCELLED;
    }

    private static void appendAttemptRecord(SubagentTask task) {
        if (task == null) {
            return;
        }
        int attempt = task.attempt;
        for (int i = task.attempts.size() - 1; i >= 0; i--) {
            if (task.attempts.get(i).attempt() == attempt) {
                return;
            }
        }
        task.attempts.add(new AttemptRecord(
                attempt,
                task.status,
                truncateText(task.summary == null ? "" : task.summary, MAX_SUMMARY_CHARS),
                truncateText(task.response == null ? "" : task.response, MAX_SUMMARY_CHARS),
                truncateText(task.error == null ? "" : task.error, MAX_SUMMARY_CHARS),
                task.startedAt,
                task.endedAt
        ));
        while (task.attempts.size() > MAX_ATTEMPT_RECORDS) {
            task.attempts.remove(0);
        }
    }

    private static void appendTrace(SubagentTask task, String toolName, JsonObject payload) {
        synchronized (task) {
            if (task.toolTrace.size() >= MAX_TRACE_ITEMS) {
                return;
            }
            String name = toolName == null ? "" : toolName;
            boolean ok = payload != null && payload.has("ok") && payload.get("ok").getAsBoolean();
            String trace = name + " -> " + (ok ? "ok" : "error");
            if (!ok && payload != null && payload.has("error")) {
                trace = trace + " (" + truncateText(payload.get("error").getAsString(), 120) + ")";
            }
            task.toolTrace.add(trace);
        }
    }

    private static List<JsonObject> deepCopyMessages(List<JsonObject> source) {
        List<JsonObject> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (JsonObject msg : source) {
            copy.add(msg == null ? new JsonObject() : msg.deepCopy());
        }
        return copy;
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
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static List<String> parseStringArray(JsonElement element) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                continue;
            }
            String value;
            try {
                value = item.getAsString();
            } catch (Exception ignored) {
                continue;
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            values.add(value.trim());
        }
        return new ArrayList<>(values);
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
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String asString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return "";
        }
        try {
            String value = obj.get(key).getAsString();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizePlanStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "pending";
        }
        return switch (raw.trim().toLowerCase()) {
            case "completed" -> "completed";
            case "in_progress" -> "in_progress";
            case "pending" -> "pending";
            default -> "pending";
        };
    }

    private static String statusName(SubagentStatus status) {
        if (status == null) {
            return "";
        }
        return status.name().toLowerCase();
    }

    private static String sessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static JsonObject ok(String toolName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        return payload;
    }

    private static JsonObject error(String toolName, String err) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        payload.addProperty("error", err == null ? "" : err);
        return payload;
    }

    private static JsonObject toolOk(String toolName) {
        return ok(toolName);
    }

    private static JsonObject toolError(String toolName, String err) {
        return error(toolName, err);
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray arr = new JsonArray();
        if (values == null) {
            return arr;
        }
        for (String value : values) {
            arr.add(value == null ? "" : value);
        }
        return arr;
    }

    private static String summarize(String response) {
        String text = response == null ? "" : response.trim();
        if (text.isBlank()) {
            return "Completed";
        }
        return truncateText(text, MAX_SUMMARY_CHARS);
    }

    private static String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    private static String formatError(Throwable ex) {
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

    private enum SubagentStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        DELETED
    }

    private static final class SubagentTask {
        String id;
        String sessionId;
        String profileId;
        String profileName;
        String projectId = "";
        boolean v2;
        List<AgentTool> tools = List.of();
        List<String> effectiveTools = List.of();
        List<String> ownedPaths = List.of();
        String task;
        SubagentStatus status = SubagentStatus.QUEUED;
        String summary = "";
        String response = "";
        String error = "";
        List<String> skillIds = new ArrayList<>();
        JsonObject metadata;
        List<String> toolTrace = new ArrayList<>();
        List<JsonObject> history = new ArrayList<>();
        ClientHistoryCompactor.Checkpoint checkpoint = ClientHistoryCompactor.Checkpoint.EMPTY;
        int attempt = 1;
        int attemptCount = 1;
        List<AttemptRecord> attempts = new ArrayList<>();
        long createdAt;
        long startedAt;
        long endedAt;
        boolean cancelRequested = false;
        /** Running core agent, so cancel/delete can abort its request. */
        Agent agent;
        /** Values scripts keep across this subagent's calls. */
        final java.util.Map<String, JsonElement> scriptStore = new ConcurrentHashMap<>();
    }

    private record TaskSnapshot(
            String id,
            String profileId,
            String profileName,
            String task,
            SubagentStatus status,
            String summary,
            String response,
            String error,
            List<String> skillIds,
            JsonObject metadata,
            List<String> toolTrace,
            int attempt,
            int attemptCount,
            List<AttemptRecord> attempts,
            long createdAt,
            long startedAt,
            long endedAt
    ) {
    }

    private record AttemptRecord(
            int attempt,
            SubagentStatus status,
            String summary,
            String response,
            String error,
            long startedAt,
            long endedAt
    ) {
    }

    private record ResolveSkillResult(
            List<String> skillIds,
            List<String> warnings
    ) {
    }
}
