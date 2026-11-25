package com.p2s.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.tool.ToolDefinition.Effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The v2 tool set: few general tools over one virtual file system, plus {@code script} and
 * {@code sh}. Extension tools registered through {@link ToolExtensions} follow the built-ins.
 */
public final class ToolCatalogV2 {
    /** Tools a script may not call: nested scripts, shells and anything that waits for the player. */
    public static final Set<String> NOT_CALLABLE_FROM_SCRIPTS = Set.of("script", "sh", "ask_user");

    private static final List<ToolDefinition> BUILTIN = build();

    private ToolCatalogV2() {
    }

    public static List<ToolDefinition> all() {
        List<ToolDefinition> tools = new ArrayList<>(BUILTIN);
        tools.addAll(ToolExtensions.definitions());
        return Collections.unmodifiableList(tools);
    }

    public static List<ToolDefinition> builtin() {
        return BUILTIN;
    }

    public static ToolDefinition find(String name) {
        for (ToolDefinition tool : BUILTIN) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return ToolExtensions.definition(name);
    }

    /** v2 tools that replace a legacy tool, for profiles written against the legacy names. */
    public static List<String> fromLegacy(String legacyName) {
        return switch (legacyName) {
            case "list_skills", "read_skill", "read_subdoc", "list_profiles", "get_profile" -> List.of("ls", "read", "find");
            case "search_skill" -> List.of("grep");
            case "get_project_state" -> List.of("project_info", "ls");
            case "read_workspace_file" -> List.of("read", "ls", "grep", "find");
            case "create_workspace_file" -> List.of("write");
            case "rename_workspace_file" -> List.of("move");
            case "delete_workspace_file" -> List.of("delete");
            case "propose_patch" -> List.of("edit", "write", "set_part");
            case "search_block_ids" -> List.of("search_block_ids");
            case "update_plan" -> List.of("update_plan");
            case "request_user_choice", "clear_user_choice" -> List.of("ask_user");
            case "create_subagent", "continue_subagent" -> List.of("task");
            case "get_subagent", "list_subagents" -> List.of("task_status");
            case "delete_subagent" -> List.of("task_cancel");
            default -> List.of(legacyName);
        };
    }

    private static List<ToolDefinition> build() {
        Map<String, ToolDefinition> tools = new LinkedHashMap<>();
        add(tools, "ls", true, Set.of(),
                "List a directory of the virtual file system. Roots: workspace/ (project workspace files; writes are staged "
                        + "for the player's approval), skills/<id>/SKILL.md and skills/<id>/subdocs/* (read only), "
                        + "agents/<profile>.json (read only). Use path \"\" for the roots.",
                """
                {"type":"object","properties":{"path":{"type":"string","description":"Directory, e.g. workspace or skills/default-builder."}},
                 "additionalProperties":false}""");
        add(tools, "read", true, Set.of(),
                "Read a text file with line numbers. Workspace files return the staged (pending) version unless committed=true. "
                        + "Long files are cut at limit lines; continue with offset.",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"File path, e.g. workspace/house.toml or skills/style-knowledge/SKILL.md."},
                  "offset":{"type":"integer","description":"First line to return, 1-based. Default 1."},
                  "limit":{"type":"integer","description":"Maximum lines to return. Default 400."},
                  "committed":{"type":"boolean","description":"Read the approved version instead of the staged one."}},
                 "required":["path"],"additionalProperties":false}""");
        add(tools, "grep", true, Set.of(),
                "Search files under a path for a regular expression (Java syntax) and return path:line:text matches.",
                """
                {"type":"object","properties":{
                  "pattern":{"type":"string","description":"Regular expression, or plain text with literal=true."},
                  "path":{"type":"string","description":"File or directory to search. Default workspace."},
                  "ignore_case":{"type":"boolean"},
                  "literal":{"type":"boolean","description":"Treat pattern as plain text."},
                  "limit":{"type":"integer","description":"Maximum matches. Default 100."}},
                 "required":["pattern"],"additionalProperties":false}""");
        add(tools, "find", true, Set.of(),
                "Find files whose full path matches a glob (* within a directory, ** across directories, ?).",
                """
                {"type":"object","properties":{
                  "pattern":{"type":"string","description":"Glob, e.g. workspace/**/*.toml or skills/*/subdocs/*roof*."},
                  "path":{"type":"string","description":"Directory to search. Default: all roots."}},
                 "required":["pattern"],"additionalProperties":false}""");
        add(tools, "edit", true, Set.of(Effect.PROJECT_STATE),
                "Replace exact text in a workspace file. Each old_text must occur exactly once in the current (staged) text; "
                        + "copy it from read without the line-number prefix and include enough lines to be unique. All edits "
                        + "apply or none do. The result is staged for the player's approval; nothing is built yet.",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Workspace file, e.g. workspace/house.toml."},
                  "edits":{"type":"array","description":"Applied in order.","items":{"type":"object","properties":{
                    "old_text":{"type":"string"},"new_text":{"type":"string"}},"required":["old_text","new_text"],
                    "additionalProperties":false}}},
                 "required":["path","edits"],"additionalProperties":false}""");
        add(tools, "write", true, Set.of(Effect.PROJECT_STATE),
                "Create a workspace file, or replace its whole content, with workspace TOML ([workspace], [palette], "
                        + "[[part]] and [[part.action]] tables). Staged for the player's approval.",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Workspace file, e.g. workspace/tower.toml."},
                  "content":{"type":"string","description":"Complete workspace TOML."}},
                 "required":["path","content"],"additionalProperties":false}""");
        add(tools, "set_part", true, Set.of(Effect.PROJECT_STATE),
                "Replace or append the actions of one part of a workspace file, creating the part if missing. Actions use the "
                        + "workspace action format: {type: box|plane|line|points, block, from, to, at, mode, axis, facing}. "
                        + "Staged for the player's approval.",
                """
                {"type":"object","properties":{
                  "path":{"type":"string"},
                  "part":{"type":"string","description":"Part name."},
                  "actions":{"type":"array","items":{"type":"object"}},
                  "mode":{"type":"string","enum":["replace","append"],"description":"Default replace."},
                  "priority":{"type":"integer"}},
                 "required":["path","part","actions"],"additionalProperties":false}""");
        add(tools, "move", true, Set.of(Effect.PROJECT_STATE),
                "Rename a workspace file. Takes effect immediately (not staged).",
                """
                {"type":"object","properties":{"path":{"type":"string"},"new_path":{"type":"string"}},
                 "required":["path","new_path"],"additionalProperties":false}""");
        add(tools, "delete", true, Set.of(Effect.PROJECT_STATE),
                "Delete a workspace file. Takes effect immediately (not staged).",
                """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""");
        add(tools, "project_info", true, Set.of(),
                "Project overview: bounds, workspace files with summaries, files with staged changes, and the selection.",
                """
                {"type":"object","properties":{},"additionalProperties":false}""");
        add(tools, "inspect_world", false, Set.of(),
                "Read the real blocks now in the world inside the project. Give a workspace path (its box) or from/to world "
                        + "coordinates. Up to 32768 blocks returns per-layer grids with a legend; larger boxes (up to 1000000) "
                        + "return block counts only.",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Workspace file whose origin/size box is inspected."},
                  "from":{"type":"array","items":{"type":"integer"},"description":"World [x, y, z]."},
                  "to":{"type":"array","items":{"type":"integer"},"description":"World [x, y, z]."},
                  "stats_only":{"type":"boolean"}},
                 "additionalProperties":false}""");
        tools.put("search_block_ids", ToolCatalog.find("search_block_ids"));
        add(tools, "ask_user", true, Set.of(Effect.SESSION_STATE, Effect.INTERACTION),
                "Ask the player to choose between 2 or 3 options. Your turn pauses until they answer; later calls in the same "
                        + "batch are not executed.",
                """
                {"type":"object","properties":{
                  "prompt":{"type":"string"},
                  "options":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"object","properties":{
                    "id":{"type":"string"},"label":{"type":"string"},"description":{"type":"string"}},"required":["label"]}}},
                 "required":["prompt","options"],"additionalProperties":false}""");
        tools.put("update_plan", ToolCatalog.find("update_plan"));
        add(tools, "task", true, Set.of(Effect.SUBAGENT_STATE),
                "Start a subagent on a delegated task, or continue a finished one by giving id and instruction. You are "
                        + "notified automatically when it finishes; use task_status for the full result instead of polling.",
                """
                {"type":"object","properties":{
                  "task":{"type":"string","description":"What the new subagent should do."},
                  "profile_id":{"type":"string","description":"Profile from agents/. Default general-planner."},
                  "skill_ids":{"type":"array","items":{"type":"string"}},
                  "metadata":{"type":"object"},
                  "id":{"type":"string","description":"Existing subagent to continue."},
                  "instruction":{"type":"string","description":"Instruction when continuing."}},
                 "additionalProperties":false}""");
        add(tools, "task_status", true, Set.of(),
                "Get one subagent's status and full response by id, or list all subagents of this session.",
                """
                {"type":"object","properties":{"id":{"type":"string"},"include_deleted":{"type":"boolean"}},
                 "additionalProperties":false}""");
        add(tools, "task_cancel", true, Set.of(Effect.SUBAGENT_STATE),
                "Stop and remove a subagent.",
                """
                {"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}""");
        add(tools, "script", true, Set.of(Effect.SESSION_STATE, Effect.PROJECT_STATE, Effect.SUBAGENT_STATE),
                """
                Run JavaScript as one tool call; only its output comes back to you. Use it to batch many reads or searches and \
                return just what you need, or to edit workspace files programmatically (loops, generated geometry). \
                Engine: Rhino ES2017-style; no async/await (calls are synchronous), no class syntax, no f(...spread) calls. \
                Globals: tools.<name>(args) calls another tool and returns its result object (throws Error on failure); \
                batch([{tool, args}, ...]) runs read-only calls concurrently and returns [{ok, result|error}]; \
                text(value), console.log(...), return value; store(key, value) / load(key) keep small JSON across scripts; \
                fs.read(path), fs.readCommitted(path), fs.write(path, text), fs.list(dir), fs.exists(path), fs.delete(path), \
                fs.move(from, to); ws.load(path) returns {workspace, palette, parts:[{name, priority, actions:[...]}]} and \
                ws.save(path, doc) writes it back; geo shapes return action arrays: geo.circle/disk({center, radius, axis?, \
                block, thickness?}), geo.sphere/dome({center, radius, block, hollow?, thickness?}), geo.cylinder({base, \
                radius, height, axis?, block, hollow?}), geo.cone({base, radius, height, block, hollow?}), geo.torus({center, \
                major, minor, block}), geo.spiral({center, radius, height, turns, block, width?}), geo.bezier({points:[2-4 \
                points], block}), geo.arch({from, to, height, block, thickness?}), geo.fromVoxels([[x,y,z,block?],...], \
                defaultBlock); transforms geo.translate(actions, [dx,dy,dz]), geo.rotateY(actions, quarterTurns, pivot?), \
                geo.mirror(actions, 'x'|'y'|'z', at), geo.scale(actions, factor). Coordinates are workspace-relative. \
                fs/ws writes are staged together only if the script succeeds; a failing or stopped script changes nothing. \
                tools.edit/write/set_part are staged immediately. Not callable from scripts: script, sh, ask_user. \
                Optional first line: // @options: {"timeout_ms": 60000, "max_output_chars": 20000}. Limits: 30 s default \
                (120 s max), 256 tool calls, 20000 actions per saved file.""",
                """
                {"type":"object","properties":{"source":{"type":"string","description":"JavaScript source (a function body; top-level return is allowed)."}},
                 "required":["source"],"additionalProperties":false}""");
        add(tools, "sh", true, Set.of(Effect.PROJECT_STATE),
                "Run a bash-like command line on the virtual file system (not an OS shell). Supports pipes, && || ;, "
                        + "> >> <, 2>/dev/null, 2>&1, quotes and globs. Commands: ls cat head tail wc grep find sed sort uniq "
                        + "cut tr cp mv rm tee echo printf diff tomlfmt true false. sed supports s///[gi N p], p, d, q, =, "
                        + "line/$//regex/ addresses and ranges, -n -i -E; regexes use Java syntax (BRE is converted). "
                        + "No variables, loops or $(...): use script for logic. diff FILE shows staged vs committed. Writes "
                        + "to workspace/ are staged together only when no command failed. 10 s timeout.",
                """
                {"type":"object","properties":{"command":{"type":"string"}},"required":["command"],"additionalProperties":false}""");
        return List.copyOf(tools.values());
    }

    private static void add(Map<String, ToolDefinition> tools, String name, boolean clientTool, Set<Effect> effects,
                            String description, String parametersJson) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description.strip());
        function.add("parameters", JsonParser.parseString(parametersJson).getAsJsonObject());
        JsonObject declaration = new JsonObject();
        declaration.addProperty("type", "function");
        declaration.add("function", function);
        tools.put(name, new ToolDefinition(name, clientTool, effects, declaration));
    }
}
