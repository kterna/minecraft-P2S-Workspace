package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.AgentToolResult;
import com.p2s.agent.script.ScriptSandbox;
import com.p2s.agent.sh.Shell;
import com.p2s.agent.tool.RegistryTools;
import com.p2s.agent.tool.ToolCatalogV2;
import com.p2s.agent.tool.ToolDefinition;
import com.p2s.agent.tool.ToolExecutors;
import com.p2s.agent.tool.ToolExtensions;
import com.p2s.agent.tool.ToolRegistry;
import com.p2s.agent.vfs.OverlayFs;
import com.p2s.agent.vfs.TextEdits;
import com.p2s.agent.vfs.VfsException;
import com.p2s.agent.vfs.VfsPaths;
import com.p2s.agent.vfs.VirtualFs;
import com.p2s.agent.vfs.WorkspaceDocs;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Client implementations of the v2 tools (see {@link ToolCatalogV2}), shared by the main agent and subagents. */
final class AgentToolsV2 {
    static final int READ_DEFAULT_LINES = 400;
    static final int READ_MAX_CHARS = 60_000;
    static final int GREP_DEFAULT_LIMIT = 100;
    static final int SHELL_MAX_OUTPUT = 40_000;

    private AgentToolsV2() {
    }

    /** What the tools need from the agent that uses them. */
    interface Host {
        /** Server bridge call fenced by the current run; failures come back as ok=false payloads. */
        JsonObject server(String tool, JsonObject args);

        /** Runs UI-state work on the client thread and waits for it. */
        JsonObject onClient(Supplier<JsonObject> action);

        String sessionId();

        /** Skills visible to this agent; empty means all. */
        Set<String> skillScope();

        /** Values kept by scripts across calls in this session. */
        Map<String, JsonElement> scriptStore();

        /** Subagents cannot create subagents or ask the player. */
        boolean isSubagent();
    }

    /** Registry plus the late-bound tool list that scripts call into. */
    record Bundle(ToolRegistry registry, AtomicReference<List<AgentTool>> tools) {
        List<AgentTool> agentTools(BooleanSupplier isActive, BiFunction<String, JsonObject, AgentToolResult.Control> control) {
            List<AgentTool> list = RegistryTools.from(registry, ToolCatalogV2.all(), isActive, control);
            tools.set(list);
            return list;
        }
    }

    static Bundle create(Host host, Collection<String> allowOnly) {
        AtomicReference<List<AgentTool>> tools = new AtomicReference<>(List.of());
        ToolRegistry.Builder builder = ToolRegistry.builder(ToolCatalogV2::find);
        if (allowOnly != null) {
            builder.allowOnly(allowOnly);
        }
        builder.register("ls", call -> ls(host, args(call)))
                .register("read", call -> read(host, args(call)))
                .register("grep", call -> grep(host, args(call)))
                .register("find", call -> find(host, args(call)))
                .register("edit", call -> edit(host, args(call)))
                .register("write", call -> write(host, args(call)))
                .register("set_part", call -> setPart(host, args(call)))
                .register("move", call -> fileOp(host, "move", fs -> fs.move(str(args(call), "path"), str(args(call), "new_path"))))
                .register("delete", call -> fileOp(host, "delete", fs -> fs.delete(str(args(call), "path"))))
                .register("project_info", call -> projectInfo(host))
                .register("inspect_world", call -> host.server("inspect_world", args(call)))
                .register("search_block_ids", call -> host.server("search_block_ids", args(call)))
                .register("update_plan", call -> host.onClient(() -> ClientAgentManager.updatePlanPayload(args(call))))
                .register("task_status", call -> taskStatus(host, args(call)))
                .register("script", call -> script(host, tools, args(call), call.signal()))
                .register("sh", call -> shell(host, args(call), call.signal()));
        if (!host.isSubagent()) {
            builder.register("ask_user", call -> host.onClient(() -> ClientAgentManager.requestUserChoicePayload(args(call))))
                    .register("task", call -> host.onClient(() -> task(host, args(call))))
                    .register("task_cancel", call -> SubagentManager.deleteSubagent(host.sessionId(), str(args(call), "id")));
        }
        for (ToolDefinition extension : ToolExtensions.definitions()) {
            builder.register(extension.name(), call -> extension(host, extension.name(), args(call), call.signal()));
        }
        return new Bundle(builder.build(), tools);
    }

    // ------------------------------------------------------------------ files

    private static ClientVirtualFs fs(Host host) {
        return new ClientVirtualFs(host::server, host.skillScope());
    }

    private static JsonObject ls(Host host, JsonObject args) throws VfsException {
        ClientVirtualFs fs = fs(host);
        String path = str(args, "path");
        JsonArray entries = new JsonArray();
        for (VirtualFs.Entry entry : fs.list(path)) {
            entries.add(entry.path() + (entry.directory() ? "/" : ""));
        }
        JsonObject payload = ok("ls");
        payload.addProperty("path", VfsPaths.normalize(path, true));
        payload.add("entries", entries);
        return payload;
    }

    private static JsonObject read(Host host, JsonObject args) throws VfsException {
        ClientVirtualFs fs = fs(host);
        String path = VfsPaths.normalize(str(args, "path"), false);
        boolean committed = bool(args, "committed");
        String text = committed ? fs.readCommitted(path) : fs.read(path);
        List<String> lines = text.isEmpty() ? List.of() : List.of(TextEdits.normalizeNewlines(text).split("\n", -1));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines = lines.subList(0, lines.size() - 1);
        }
        int offset = Math.max(1, integer(args, "offset", 1));
        int limit = Math.max(1, Math.min(5_000, integer(args, "limit", READ_DEFAULT_LINES)));
        StringBuilder content = new StringBuilder();
        int last = offset - 1;
        for (int i = offset - 1; i < lines.size() && i < offset - 1 + limit; i++) {
            String line = String.format("%6d\t%s\n", i + 1, lines.get(i));
            if (content.length() + line.length() > READ_MAX_CHARS) {
                break;
            }
            content.append(line);
            last = i + 1;
        }
        JsonObject payload = ok("read");
        payload.addProperty("path", path);
        payload.addProperty("total_lines", lines.size());
        payload.addProperty("from_line", Math.min(offset, Math.max(1, lines.size())));
        payload.addProperty("to_line", last);
        if (last < lines.size()) {
            payload.addProperty("truncated", true);
            payload.addProperty("next_offset", last + 1);
        }
        if (committed) {
            payload.addProperty("version", "committed");
        }
        payload.addProperty("content", content.toString());
        return payload;
    }

    private static JsonObject grep(Host host, JsonObject args) throws VfsException {
        ClientVirtualFs fs = fs(host);
        String raw = str(args, "pattern");
        if (raw.isEmpty()) {
            return error("grep", "pattern is required");
        }
        Pattern pattern;
        try {
            int flags = bool(args, "ignore_case") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            pattern = Pattern.compile(bool(args, "literal") ? Pattern.quote(raw) : raw, flags);
        } catch (PatternSyntaxException ex) {
            return error("grep", "invalid regular expression: " + ex.getDescription());
        }
        String root = args.has("path") ? str(args, "path") : VfsPaths.WORKSPACE;
        List<String> files = fs.isDirectory(root) ? fs.walk(root) : List.of(VfsPaths.normalize(root, false));
        int limit = Math.max(1, Math.min(1_000, integer(args, "limit", GREP_DEFAULT_LIMIT)));
        JsonArray matches = new JsonArray();
        boolean truncated = false;
        outer:
        for (String file : files) {
            String text = fs.read(file);
            String[] lines = TextEdits.normalizeNewlines(text).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = pattern.matcher(lines[i]);
                if (matcher.find()) {
                    if (matches.size() >= limit) {
                        truncated = true;
                        break outer;
                    }
                    String line = lines[i].length() > 300 ? lines[i].substring(0, 300) + "..." : lines[i];
                    matches.add(file + ":" + (i + 1) + ":" + line);
                }
            }
        }
        JsonObject payload = ok("grep");
        payload.addProperty("count", matches.size());
        payload.add("matches", matches);
        if (truncated) {
            payload.addProperty("truncated", true);
        }
        return payload;
    }

    private static JsonObject find(Host host, JsonObject args) throws VfsException {
        ClientVirtualFs fs = fs(host);
        String pattern = str(args, "pattern");
        if (pattern.isEmpty()) {
            return error("find", "pattern is required");
        }
        List<String> files = new ArrayList<>();
        if (args.has("path")) {
            files.addAll(fs.walk(str(args, "path")));
        } else {
            for (String root : List.of(VfsPaths.WORKSPACE, VfsPaths.SKILLS, VfsPaths.AGENTS)) {
                files.addAll(fs.walk(root));
            }
        }
        boolean leafOnly = !pattern.contains("/");
        JsonArray found = new JsonArray();
        for (String file : files) {
            String target = leafOnly ? file.substring(file.lastIndexOf('/') + 1) : file;
            if (VfsPaths.globMatches(pattern, target) && found.size() < 500) {
                found.add(file);
            }
        }
        JsonObject payload = ok("find");
        payload.addProperty("count", found.size());
        payload.add("files", found);
        return payload;
    }

    private static JsonObject edit(Host host, JsonObject args) throws VfsException {
        ClientVirtualFs fs = fs(host);
        String path = VfsPaths.normalize(str(args, "path"), false);
        List<TextEdits.Edit> edits = new ArrayList<>();
        if (args.has("edits") && args.get("edits").isJsonArray()) {
            for (JsonElement element : args.getAsJsonArray("edits")) {
                JsonObject edit = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
                edits.add(new TextEdits.Edit(str(edit, "old_text"), str(edit, "new_text")));
            }
        }
        String updated = TextEdits.apply(fs.read(path), edits);
        fs.write(path, updated);
        return staged("edit", fs, path);
    }

    private static JsonObject write(Host host, JsonObject args) throws VfsException {
        ClientVirtualFs fs = fs(host);
        String path = VfsPaths.normalize(str(args, "path"), false);
        fs.write(path, str(args, "content"));
        return staged("write", fs, path);
    }

    private static JsonObject setPart(Host host, JsonObject args) throws VfsException {
        ClientVirtualFs fs = fs(host);
        String path = VfsPaths.normalize(str(args, "path"), false);
        if (!fs.exists(path)) {
            return error("set_part", "No such file: " + path + " (create it with write first)");
        }
        JsonArray actions = args.has("actions") && args.get("actions").isJsonArray() ? args.getAsJsonArray("actions") : new JsonArray();
        Integer priority = args.has("priority") && args.get("priority").isJsonPrimitive() ? args.get("priority").getAsInt() : null;
        boolean append = "append".equals(str(args, "mode"));
        fs.write(path, WorkspaceDocs.setPart(fs.read(path), str(args, "part"), actions, append, priority));
        return staged("set_part", fs, path);
    }

    /** Tool result after a write: what the server staged for approval. */
    private static JsonObject staged(String tool, ClientVirtualFs fs, String path) {
        JsonObject payload = ok(tool);
        payload.addProperty("path", path);
        List<JsonObject> results = fs.stageResults();
        if (!results.isEmpty()) {
            JsonObject last = results.getLast();
            for (String key : List.of("staged", "staged_revision", "changed_blocks", "risk", "summary", "message", "warnings")) {
                if (last.has(key)) {
                    payload.add(key, last.get(key));
                }
            }
        }
        payload.addProperty("note", "Staged for the player's approval at the end of your turn; not built yet.");
        return payload;
    }

    @FunctionalInterface
    private interface FsAction {
        void run(VirtualFs fs) throws VfsException;
    }

    private static JsonObject fileOp(Host host, String tool, FsAction action) throws VfsException {
        action.run(fs(host));
        return ok(tool);
    }

    private static JsonObject projectInfo(Host host) {
        JsonObject state = host.server("get_project_state", new JsonObject());
        if (AgentToolResult.isErrorPayload(state)) {
            return state;
        }
        JsonObject payload = ok("project_info");
        for (String key : List.of("project", "summary")) {
            if (state.has(key)) {
                payload.add(key, state.get(key));
            }
        }
        JsonArray files = new JsonArray();
        if (state.has("workspace_files") && state.get("workspace_files").isJsonArray()) {
            for (JsonElement element : state.getAsJsonArray("workspace_files")) {
                JsonObject file = element.getAsJsonObject().deepCopy();
                file.addProperty("path", ClientVirtualFs.toVfs(str(file, "path")));
                files.add(file);
            }
        }
        payload.add("workspace_files", files);
        JsonArray pending = new JsonArray();
        for (String serverPath : ClientVirtualFs.pendingPaths(state)) {
            pending.add(ClientVirtualFs.toVfs(serverPath));
        }
        payload.add("staged_files", pending);
        BlockPos p1 = ClientSelectionManager.getPos1();
        BlockPos p2 = ClientSelectionManager.getPos2();
        if (p1 != null && p2 != null) {
            JsonObject selection = new JsonObject();
            selection.add("from", vec(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ())));
            selection.add("to", vec(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ())));
            payload.add("selection", selection);
        }
        return payload;
    }

    // ------------------------------------------------------------------ subagents

    private static JsonObject task(Host host, JsonObject args) {
        if (args.has("id") && !str(args, "id").isBlank()) {
            JsonObject continued = new JsonObject();
            continued.addProperty("id", str(args, "id"));
            continued.addProperty("instruction", str(args, "instruction"));
            return SubagentManager.continueSubagent(host.sessionId(), continued);
        }
        return SubagentManager.createSubagent(host.sessionId(), args);
    }

    private static JsonObject taskStatus(Host host, JsonObject args) {
        String id = str(args, "id");
        return id.isBlank()
                ? SubagentManager.listSubagents(host.sessionId(), bool(args, "include_deleted"))
                : SubagentManager.getSubagent(host.sessionId(), id);
    }

    // ------------------------------------------------------------------ script and sh

    private static JsonObject script(Host host, AtomicReference<List<AgentTool>> tools, JsonObject args, AbortSignal signal) {
        List<AgentTool> callable = new ArrayList<>();
        for (AgentTool tool : tools.get()) {
            if (!ToolCatalogV2.NOT_CALLABLE_FROM_SCRIPTS.contains(tool.name())) {
                callable.add(tool);
            }
        }
        ScriptSandbox.Result result = ScriptSandbox.run(str(args, "source"), callable, fs(host), host.scriptStore(), signal,
                ToolExecutors.shared(), ScriptSandbox.Options.defaults());
        if (result.ok()) {
            result.storeUpdates().forEach((key, value) -> {
                if (value == null || value.isJsonNull()) {
                    host.scriptStore().remove(key);
                } else {
                    host.scriptStore().put(key, value);
                }
            });
        }
        return result.toPayload(64);
    }

    private static JsonObject shell(Host host, JsonObject args, AbortSignal signal) {
        Shell.Result result = Shell.run(str(args, "command"), fs(host), signal);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", !result.failed());
        payload.addProperty("tool", "sh");
        payload.addProperty("exit_code", result.exitCode());
        payload.addProperty("stdout", clip(result.stdout(), SHELL_MAX_OUTPUT));
        if (!result.stderr().isEmpty()) {
            payload.addProperty("stderr", clip(result.stderr(), 8_000));
        }
        if (result.failed()) {
            payload.addProperty("error", result.stderr().isBlank() ? "command failed" : result.stderr().strip());
        }
        if (!result.commits().isEmpty()) {
            JsonArray files = new JsonArray();
            for (OverlayFs.CommitResult commit : result.commits()) {
                files.add(commit.path() + ": " + commit.message());
            }
            payload.add("files", files);
        }
        return payload;
    }

    // ------------------------------------------------------------------ extensions

    private static JsonObject extension(Host host, String name, JsonObject args, AbortSignal signal) throws Exception {
        ToolExtensions.Handler handler = ToolExtensions.handler(name);
        if (handler == null) {
            return error(name, "Extension tool is no longer registered");
        }
        ClientVirtualFs fs = fs(host);
        JsonObject result = handler.execute(new ToolExtensions.Context() {
            @Override
            public VirtualFs fs() {
                return fs;
            }

            @Override
            public JsonObject callServer(String tool, JsonObject arguments) {
                return host.server(tool, arguments);
            }

            @Override
            public String sessionId() {
                return host.sessionId();
            }

            @Override
            public AbortSignal signal() {
                return signal;
            }
        }, args);
        return result == null ? error(name, "Extension returned no result") : result;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject args(ToolRegistry.Invocation call) {
        JsonElement arguments = call.arguments();
        return arguments != null && arguments.isJsonObject() ? arguments.getAsJsonObject() : new JsonObject();
    }

    static String str(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    private static String clip(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit * 2 / 3) + "\n...[" + (text.length() - limit) + " characters omitted]...\n"
                + text.substring(text.length() - limit / 3);
    }

    static JsonObject ok(String tool) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", tool);
        return payload;
    }

    static JsonObject error(String tool, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", tool);
        payload.addProperty("error", message);
        return payload;
    }
}
