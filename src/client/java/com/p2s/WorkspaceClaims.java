package com.p2s;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.p2s.agent.vfs.VfsException;
import com.p2s.agent.vfs.VfsPaths;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Local agent reservations. Start/continue and the final network send run on the Minecraft client thread. */
final class WorkspaceClaims {
    private record File(String project, String path) {}
    private final Map<File, String> owners = new HashMap<>();

    static List<String> parse(JsonElement paths) throws VfsException {
        if (paths == null) return List.of();
        if (!paths.isJsonArray()) throw new VfsException("owned_paths must be an array of workspace files");
        var normalized = new LinkedHashSet<String>();
        for (JsonElement item : paths.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new VfsException("owned_paths requires workspace file paths");
            }
            String path = VfsPaths.normalize(item.getAsString(), false);
            if (!VfsPaths.isWorkspaceFile(path)) throw new VfsException("Expected workspace file: " + path);
            normalized.add(path);
        }
        return List.copyOf(normalized);
    }

    synchronized void reserve(String project, String owner, List<String> paths) throws VfsException {
        for (String path : paths) check(project, owner, path);
        for (String path : paths) owners.put(new File(project, path), owner);
    }

    synchronized void release(String project, String owner, List<String> paths) {
        for (String path : paths) owners.remove(new File(project, path), owner);
    }

    private void check(String project, String actor, String path) throws VfsException {
        String owner = owners.get(new File(project, path));
        if (owner != null && !owner.equals(actor)) {
            throw new VfsException(path + " is assigned to subagent " + owner + "; wait for task_status before writing");
        }
    }

    synchronized void checkMutation(String project, String actor, String tool, JsonObject args, String selected) throws VfsException {
        switch (tool == null ? "" : tool.trim()) {
            case "rename_workspace_file" -> check(project, actor, canonical(AgentToolsV2.str(args, "new_path")));
            case "stage_workspace_file", "save_workspace_file", "create_workspace_file", "delete_workspace_file", "propose_patch" -> { }
            default -> { return; }
        }
        String path = AgentToolsV2.str(args, "path");
        check(project, actor, canonical(path.isBlank() ? selected : path));
    }

    private static String canonical(String path) throws VfsException {
        return VfsPaths.normalize(ClientVirtualFs.toVfs(path), false);
    }
}
