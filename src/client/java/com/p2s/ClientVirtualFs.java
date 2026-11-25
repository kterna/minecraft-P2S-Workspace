package com.p2s;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.agent.core.AgentToolResult;
import com.p2s.agent.vfs.VfsException;
import com.p2s.agent.vfs.VfsPaths;
import com.p2s.agent.vfs.VirtualFs;
import com.p2s.agent.vfs.WorkspaceDocs;
import com.p2s.store.SkillStore;
import com.p2s.store.SubagentProfileStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Client view of the v2 virtual file system: {@code workspace/} through the server tool bridge
 * (reads return the staged version, writes stage for approval), {@code skills/} and
 * {@code agents/} from the local stores (read only). One instance serves one tool call or one
 * script, so the workspace listing and revisions are cached for its lifetime.
 */
final class ClientVirtualFs implements VirtualFs {
    private final BiFunction<String, JsonObject, JsonObject> server;
    private final Set<String> skillScope;
    private Map<String, String> workspaceFiles;
    private final Map<String, String> stagedRevisions = new LinkedHashMap<>();
    private final List<JsonObject> stageResults = new ArrayList<>();

    /**
     * @param server     calls a server bridge tool and returns its payload (errors as ok=false)
     * @param skillScope skills visible to a scoped subagent; empty means all
     */
    ClientVirtualFs(BiFunction<String, JsonObject, JsonObject> server, Set<String> skillScope) {
        this.server = server;
        this.skillScope = skillScope == null ? Set.of() : skillScope;
    }

    /** Server responses of the writes made through this instance, for tool results. */
    List<JsonObject> stageResults() {
        return List.copyOf(stageResults);
    }

    // ------------------------------------------------------------------ listing

    @Override
    public List<Entry> list(String dir) throws VfsException {
        String normalized = VfsPaths.normalize(dir, true);
        if (normalized.isEmpty()) {
            return List.of(new Entry(VfsPaths.WORKSPACE, true), new Entry(VfsPaths.SKILLS, true),
                    new Entry(VfsPaths.AGENTS, true));
        }
        List<String> files = switch (VfsPaths.root(normalized)) {
            case VfsPaths.WORKSPACE -> new ArrayList<>(workspace().keySet());
            case VfsPaths.SKILLS -> skillFiles();
            default -> agentFiles();
        };
        String prefix = normalized + "/";
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String file : files) {
            if (!file.startsWith(prefix)) {
                continue;
            }
            String rest = file.substring(prefix.length());
            int slash = rest.indexOf('/');
            String child = slash < 0 ? file : prefix + rest.substring(0, slash);
            entries.putIfAbsent(child, new Entry(child, slash >= 0));
        }
        if (entries.isEmpty() && !VfsPaths.ROOTS.contains(normalized)) {
            if (files.contains(normalized)) {
                throw new VfsException("Not a directory: " + normalized);
            }
            throw new VfsException("No such directory: " + normalized);
        }
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean exists(String path) {
        try {
            String normalized = VfsPaths.normalize(path, false);
            return files(VfsPaths.root(normalized)).contains(normalized) || isDirectory(normalized);
        } catch (VfsException ex) {
            return false;
        }
    }

    @Override
    public boolean isDirectory(String path) {
        try {
            String normalized = VfsPaths.normalize(path, true);
            if (normalized.isEmpty() || VfsPaths.ROOTS.contains(normalized)) {
                return true;
            }
            String prefix = normalized + "/";
            for (String file : files(VfsPaths.root(normalized))) {
                if (file.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        } catch (VfsException ex) {
            return false;
        }
    }

    private List<String> files(String root) throws VfsException {
        return switch (root) {
            case VfsPaths.WORKSPACE -> new ArrayList<>(workspace().keySet());
            case VfsPaths.SKILLS -> skillFiles();
            default -> agentFiles();
        };
    }

    /** VFS path → server path of every workspace file in the current project. */
    private Map<String, String> workspace() throws VfsException {
        if (workspaceFiles == null) {
            JsonObject state = call("get_project_state", new JsonObject());
            Map<String, String> files = new LinkedHashMap<>();
            if (state.has("workspace_files") && state.get("workspace_files").isJsonArray()) {
                for (JsonElement element : state.getAsJsonArray("workspace_files")) {
                    if (element.isJsonObject() && element.getAsJsonObject().has("path")) {
                        String serverPath = element.getAsJsonObject().get("path").getAsString();
                        files.put(toVfs(serverPath), serverPath);
                    }
                }
            }
            workspaceFiles = files;
        }
        return workspaceFiles;
    }

    /** Same normalization as ProjectPersistence.normalizeWorkspacePath, without loading that class. */
    static String toVfs(String serverPath) {
        String p = serverPath == null ? "" : serverPath.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        p = p.replaceAll("/{2,}", "/");
        return p.startsWith(VfsPaths.WORKSPACE + "/") ? p : VfsPaths.WORKSPACE + "/" + p;
    }

    private String toServer(String vfsPath) throws VfsException {
        String known = workspace().get(vfsPath);
        return known != null ? known : vfsPath;
    }

    private List<String> skillFiles() {
        List<String> files = new ArrayList<>();
        for (SkillStore.SkillMeta meta : SkillStore.listSkills()) {
            if (!skillScope.isEmpty() && !skillScope.contains(meta.id())) {
                continue;
            }
            files.add(VfsPaths.SKILLS + "/" + meta.id() + "/SKILL.md");
            for (SkillStore.SubdocMeta subdoc : SkillStore.listSubdocs(meta.id())) {
                files.add(VfsPaths.SKILLS + "/" + meta.id() + "/subdocs/" + subdoc.path());
            }
        }
        return files;
    }

    private static List<String> agentFiles() {
        List<String> files = new ArrayList<>();
        for (SubagentProfileStore.ProfileSummary profile : SubagentProfileStore.listProfileSummaries()) {
            files.add(VfsPaths.AGENTS + "/" + profile.id() + ".json");
        }
        return files;
    }

    // ------------------------------------------------------------------ reading

    @Override
    public String read(String path) throws VfsException {
        return read(path, false);
    }

    @Override
    public String readCommitted(String path) throws VfsException {
        return read(path, true);
    }

    private String read(String path, boolean committed) throws VfsException {
        String normalized = VfsPaths.normalize(path, false);
        switch (VfsPaths.root(normalized)) {
            case VfsPaths.WORKSPACE -> {
                if (!workspace().containsKey(normalized)) {
                    throw new VfsException(isDirectory(normalized) ? "Is a directory: " + normalized
                            : "No such file: " + normalized);
                }
                JsonObject args = new JsonObject();
                args.addProperty("path", toServer(normalized));
                args.addProperty("committed", committed);
                JsonObject text = call("workspace_text", args);
                if (!committed && text.has("staged_revision")) {
                    stagedRevisions.put(normalized, text.get("staged_revision").getAsString());
                }
                return text.has("workspace_toml") ? text.get("workspace_toml").getAsString() : "";
            }
            case VfsPaths.SKILLS -> {
                return readSkillFile(normalized);
            }
            default -> {
                String id = normalized.substring((VfsPaths.AGENTS + "/").length());
                if (!id.endsWith(".json") || id.contains("/")) {
                    throw new VfsException("No such file: " + normalized);
                }
                JsonObject profile = SubagentManager.getProfile(id.substring(0, id.length() - 5));
                if (AgentToolResult.isErrorPayload(profile)) {
                    throw new VfsException("No such file: " + normalized);
                }
                profile.remove("ok");
                profile.remove("tool");
                return new GsonBuilder().setPrettyPrinting().create().toJson(profile) + "\n";
            }
        }
    }

    private String readSkillFile(String normalized) throws VfsException {
        String rest = normalized.substring((VfsPaths.SKILLS + "/").length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new VfsException("Is a directory: " + normalized);
        }
        String id = rest.substring(0, slash);
        String file = rest.substring(slash + 1);
        if (!skillScope.isEmpty() && !skillScope.contains(id)) {
            throw new VfsException("Skill not available to this agent: " + id);
        }
        if (file.equals("SKILL.md")) {
            SkillStore.SkillDocument doc = SkillStore.readSkill(id);
            if (doc == null) {
                throw new VfsException("No such file: " + normalized);
            }
            return "---\nname: " + doc.meta().name() + "\ndescription: " + doc.meta().description() + "\n---\n\n"
                    + (doc.body() == null ? "" : doc.body());
        }
        if (file.startsWith("subdocs/")) {
            SkillStore.SubdocDocument doc = SkillStore.readSubdoc(id, file.substring("subdocs/".length()));
            if (doc != null) {
                return doc.body() == null ? "" : doc.body();
            }
        }
        throw new VfsException("No such file: " + normalized);
    }

    // ------------------------------------------------------------------ writing

    @Override
    public void write(String path, String content) throws VfsException {
        String normalized = requireWorkspace(path);
        String toml = content == null ? "" : content;
        JsonObject document = WorkspaceDocs.parse(toml);
        if (!workspace().containsKey(normalized)) {
            JsonObject create = new JsonObject();
            String leaf = normalized.substring(normalized.lastIndexOf('/') + 1);
            create.addProperty("path", normalized);
            create.addProperty("name", leaf);
            String type = document.has("workspace") && document.getAsJsonObject("workspace").has("type")
                    ? document.getAsJsonObject("workspace").get("type").getAsString() : "manual";
            create.addProperty("type", type.isBlank() ? "manual" : type);
            call("create_workspace_file", create);
            workspace().put(normalized, normalized);
            stagedRevisions.remove(normalized);
        }
        JsonObject args = new JsonObject();
        args.addProperty("path", toServer(normalized));
        args.addProperty("workspace_toml", toml);
        String base = stagedRevisions.get(normalized);
        if (base != null) {
            args.addProperty("base_revision", base);
        }
        JsonObject result = call("stage_workspace_file", args);
        if (result.has("staged_revision")) {
            stagedRevisions.put(normalized, result.get("staged_revision").getAsString());
        }
        result.addProperty("path", normalized);
        stageResults.add(result);
    }

    @Override
    public void delete(String path) throws VfsException {
        String normalized = requireWorkspace(path);
        if (!workspace().containsKey(normalized)) {
            throw new VfsException("No such file: " + normalized);
        }
        JsonObject args = new JsonObject();
        args.addProperty("path", toServer(normalized));
        call("delete_workspace_file", args);
        workspace().remove(normalized);
        stagedRevisions.remove(normalized);
    }

    @Override
    public void move(String from, String to) throws VfsException {
        String source = requireWorkspace(from);
        String target = requireWorkspace(to);
        if (!workspace().containsKey(source)) {
            throw new VfsException("No such file: " + source);
        }
        if (workspace().containsKey(target)) {
            throw new VfsException("Target exists: " + target);
        }
        JsonObject args = new JsonObject();
        args.addProperty("path", toServer(source));
        args.addProperty("new_path", target);
        call("rename_workspace_file", args);
        workspace().remove(source);
        workspace().put(target, target);
        stagedRevisions.remove(source);
    }

    private static String requireWorkspace(String path) throws VfsException {
        String normalized = VfsPaths.normalize(path, false);
        if (!VfsPaths.isWorkspaceFile(normalized)) {
            throw new VfsException("Read-only path: " + normalized + " (only workspace/ is writable)");
        }
        return normalized;
    }

    private JsonObject call(String tool, JsonObject args) throws VfsException {
        JsonObject payload = server.apply(tool, args);
        if (payload == null) {
            throw new VfsException(tool + ": empty server response");
        }
        if (AgentToolResult.isErrorPayload(payload)) {
            StringBuilder message = new StringBuilder(payload.has("error") ? payload.get("error").getAsString() : tool + " failed");
            if (payload.has("errors") && payload.get("errors").isJsonArray()) {
                JsonArray errors = payload.getAsJsonArray("errors");
                if (errors.size() > 1) {
                    message.append(" (").append(errors.size()).append(" errors)");
                }
            }
            throw new VfsException(message.toString());
        }
        return payload;
    }

    /** Workspace files that currently have a pending (staged) version, from the server listing. */
    static Set<String> pendingPaths(JsonObject projectState) {
        Set<String> pending = new LinkedHashSet<>();
        if (projectState != null && projectState.has("pending_paths") && projectState.get("pending_paths").isJsonArray()) {
            for (JsonElement element : projectState.getAsJsonArray("pending_paths")) {
                pending.add(element.getAsString());
            }
        }
        return pending;
    }
}
