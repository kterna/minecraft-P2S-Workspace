package com.p2s.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider-independent tool definitions in their original request order. */
public final class ToolCatalog {
    private static final List<ToolDefinition> TOOLS = buildTools();
    private static final Map<String, ToolDefinition> BY_NAME = indexTools();

    private ToolCatalog() {
    }

    public static List<ToolDefinition> all() {
        return TOOLS;
    }

    public static ToolDefinition find(String name) {
        return BY_NAME.get(name);
    }

    public static JsonArray definitions(boolean includeClientTools, Collection<String> allowedTools) {
        Set<String> allowed = normalizeAllowedNames(allowedTools);
        JsonArray definitions = new JsonArray();
        for (ToolDefinition tool : TOOLS) {
            if ((!tool.clientTool() || includeClientTools)
                    && (allowed == null || allowed.contains(tool.name()))) {
                definitions.add(tool.declaration());
            }
        }
        return definitions;
    }

    public static Set<String> normalizeAllowedNames(Collection<String> names) {
        if (names == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim());
            }
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, ToolDefinition> indexTools() {
        Map<String, ToolDefinition> tools = new LinkedHashMap<>();
        for (ToolDefinition tool : TOOLS) {
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate tool definition: " + tool.name());
            }
        }
        return Collections.unmodifiableMap(tools);
    }

    private static List<ToolDefinition> buildTools() {
        List<ToolDefinition> tools = new ArrayList<>();

        {
            JsonObject listSkillTool = new JsonObject();
            listSkillTool.addProperty("type", "function");
            JsonObject listSkillFn = new JsonObject();
            listSkillFn.addProperty("name", "list_skills");
            listSkillFn.addProperty("description", "List available player skills with short metadata only.");
            JsonObject listSkillParams = new JsonObject();
            listSkillParams.addProperty("type", "object");
            listSkillParams.add("properties", new JsonObject());
            listSkillParams.addProperty("additionalProperties", false);
            listSkillFn.add("parameters", listSkillParams);
            listSkillTool.add("function", listSkillFn);
            tools.add(new ToolDefinition("list_skills", true, Set.of(), listSkillTool));
        }

        {
            JsonObject readSkillTool = new JsonObject();
            readSkillTool.addProperty("type", "function");
            JsonObject readSkillFn = new JsonObject();
            readSkillFn.addProperty("name", "read_skill");
            readSkillFn.addProperty("description", "Read one skill document by id. If id omitted, the active skill may be used.");
            JsonObject readSkillParams = new JsonObject();
            readSkillParams.addProperty("type", "object");
            JsonObject readSkillProps = new JsonObject();
            JsonObject skillId = new JsonObject();
            skillId.addProperty("type", "string");
            skillId.addProperty("description", "Skill id from list_skills.");
            readSkillProps.add("id", skillId);
            readSkillParams.add("properties", readSkillProps);
            readSkillParams.addProperty("additionalProperties", false);
            readSkillFn.add("parameters", readSkillParams);
            readSkillTool.add("function", readSkillFn);
            tools.add(new ToolDefinition("read_skill", true, Set.of(), readSkillTool));
        }

        {
            JsonObject readSubdocTool = new JsonObject();
            readSubdocTool.addProperty("type", "function");
            JsonObject readSubdocFn = new JsonObject();
            readSubdocFn.addProperty("name", "read_subdoc");
            readSubdocFn.addProperty("description", "Read one markdown sub-document inside a skill directory by relative path.");
            JsonObject readSubdocParams = new JsonObject();
            readSubdocParams.addProperty("type", "object");
            JsonObject readSubdocProps = new JsonObject();
            JsonObject readSubdocId = new JsonObject();
            readSubdocId.addProperty("type", "string");
            readSubdocId.addProperty("description", "Optional skill id from list_skills. Defaults to active skill.");
            readSubdocProps.add("id", readSubdocId);
            JsonObject readSubdocPath = new JsonObject();
            readSubdocPath.addProperty("type", "string");
            readSubdocPath.addProperty("description", "Relative markdown path, for example subdocs/roof-gable.md.");
            readSubdocProps.add("path", readSubdocPath);
            readSubdocParams.add("properties", readSubdocProps);
            JsonArray readSubdocRequired = new JsonArray();
            readSubdocRequired.add("path");
            readSubdocParams.add("required", readSubdocRequired);
            readSubdocParams.addProperty("additionalProperties", false);
            readSubdocFn.add("parameters", readSubdocParams);
            readSubdocTool.add("function", readSubdocFn);
            tools.add(new ToolDefinition("read_subdoc", true, Set.of(), readSubdocTool));
        }

        {
            JsonObject searchSkillTool = new JsonObject();
            searchSkillTool.addProperty("type", "function");
            JsonObject searchSkillFn = new JsonObject();
            searchSkillFn.addProperty("name", "search_skill");
            searchSkillFn.addProperty("description", "Search text snippets in a skill by query.");
            JsonObject searchSkillParams = new JsonObject();
            searchSkillParams.addProperty("type", "object");
            JsonObject searchSkillProps = new JsonObject();
            JsonObject searchSkillId = new JsonObject();
            searchSkillId.addProperty("type", "string");
            searchSkillId.addProperty("description", "Optional skill id. Defaults to active skill.");
            searchSkillProps.add("id", searchSkillId);
            JsonObject searchSkillQuery = new JsonObject();
            searchSkillQuery.addProperty("type", "string");
            searchSkillQuery.addProperty("description", "Keyword to search.");
            searchSkillProps.add("query", searchSkillQuery);
            JsonObject searchSkillLimit = new JsonObject();
            searchSkillLimit.addProperty("type", "integer");
            searchSkillLimit.addProperty("description", "Max snippets to return (1-50).");
            searchSkillProps.add("limit", searchSkillLimit);
            searchSkillParams.add("properties", searchSkillProps);
            JsonArray searchSkillRequired = new JsonArray();
            searchSkillRequired.add("query");
            searchSkillParams.add("required", searchSkillRequired);
            searchSkillParams.addProperty("additionalProperties", false);
            searchSkillFn.add("parameters", searchSkillParams);
            searchSkillTool.add("function", searchSkillFn);
            tools.add(new ToolDefinition("search_skill", true, Set.of(), searchSkillTool));
        }

        {
            JsonObject planTool = new JsonObject();
            planTool.addProperty("type", "function");
            JsonObject planFn = new JsonObject();
            planFn.addProperty("name", "update_plan");
            planFn.addProperty("description", "Updates the task plan. Provide an optional explanation and a list of plan items, each with a step and status. At most one step can be in_progress at a time.");
            JsonObject planParams = new JsonObject();
            planParams.addProperty("type", "object");
            JsonObject planProps = new JsonObject();

            JsonObject explanation = new JsonObject();
            explanation.addProperty("type", "string");
            planProps.add("explanation", explanation);

            JsonObject stepStatus = new JsonObject();
            stepStatus.addProperty("type", "string");
            JsonArray statusEnum = new JsonArray();
            statusEnum.add("pending");
            statusEnum.add("in_progress");
            statusEnum.add("completed");
            stepStatus.add("enum", statusEnum);
            stepStatus.addProperty("description", "One of: pending, in_progress, completed.");

            JsonObject plan = new JsonObject();
            plan.addProperty("type", "array");
            plan.addProperty("description", "The list of steps.");
            JsonObject item = new JsonObject();
            item.addProperty("type", "object");
            JsonObject itemProps = new JsonObject();
            JsonObject step = new JsonObject();
            step.addProperty("type", "string");
            itemProps.add("step", step);
            itemProps.add("status", stepStatus);
            item.add("properties", itemProps);
            JsonArray itemRequired = new JsonArray();
            itemRequired.add("step");
            itemRequired.add("status");
            item.add("required", itemRequired);
            item.addProperty("additionalProperties", false);
            plan.add("items", item);
            planProps.add("plan", plan);

            planParams.add("properties", planProps);
            JsonArray required = new JsonArray();
            required.add("plan");
            planParams.add("required", required);
            planParams.addProperty("additionalProperties", false);
            planFn.add("parameters", planParams);
            planTool.add("function", planFn);
            tools.add(new ToolDefinition("update_plan", true, Set.of(ToolDefinition.Effect.SESSION_STATE), planTool));
        }

        {
            JsonObject requestChoiceTool = new JsonObject();
            requestChoiceTool.addProperty("type", "function");
            JsonObject requestChoiceFn = new JsonObject();
            requestChoiceFn.addProperty("name", "request_user_choice");
            requestChoiceFn.addProperty("description", "Request explicit user approval/selection from 2-3 options, then wait for user choice.");
            JsonObject requestChoiceParams = new JsonObject();
            requestChoiceParams.addProperty("type", "object");
            JsonObject requestChoiceProps = new JsonObject();
            JsonObject choicePrompt = new JsonObject();
            choicePrompt.addProperty("type", "string");
            choicePrompt.addProperty("description", "Question shown to user.");
            requestChoiceProps.add("prompt", choicePrompt);
            JsonObject choiceRequestId = new JsonObject();
            choiceRequestId.addProperty("type", "string");
            choiceRequestId.addProperty("description", "Optional stable choice request id.");
            requestChoiceProps.add("request_id", choiceRequestId);
            JsonObject choiceOptions = new JsonObject();
            choiceOptions.addProperty("type", "array");
            JsonObject choiceOptionItem = new JsonObject();
            choiceOptionItem.addProperty("type", "object");
            JsonObject choiceOptionProps = new JsonObject();
            JsonObject choiceOptionId = new JsonObject();
            choiceOptionId.addProperty("type", "string");
            choiceOptionId.addProperty("description", "Option id.");
            choiceOptionProps.add("id", choiceOptionId);
            JsonObject choiceLabel = new JsonObject();
            choiceLabel.addProperty("type", "string");
            choiceLabel.addProperty("description", "Short label shown on button.");
            choiceOptionProps.add("label", choiceLabel);
            JsonObject choiceDescription = new JsonObject();
            choiceDescription.addProperty("type", "string");
            choiceDescription.addProperty("description", "Optional option detail.");
            choiceOptionProps.add("description", choiceDescription);
            choiceOptionItem.add("properties", choiceOptionProps);
            JsonArray choiceOptionRequired = new JsonArray();
            choiceOptionRequired.add("label");
            choiceOptionItem.add("required", choiceOptionRequired);
            choiceOptions.add("items", choiceOptionItem);
            requestChoiceProps.add("options", choiceOptions);
            requestChoiceParams.add("properties", requestChoiceProps);
            JsonArray requestChoiceRequired = new JsonArray();
            requestChoiceRequired.add("prompt");
            requestChoiceRequired.add("options");
            requestChoiceParams.add("required", requestChoiceRequired);
            requestChoiceParams.addProperty("additionalProperties", false);
            requestChoiceFn.add("parameters", requestChoiceParams);
            requestChoiceTool.add("function", requestChoiceFn);
            tools.add(new ToolDefinition("request_user_choice", true, Set.of(ToolDefinition.Effect.SESSION_STATE, ToolDefinition.Effect.INTERACTION), requestChoiceTool));
        }

        {
            JsonObject clearChoiceTool = new JsonObject();
            clearChoiceTool.addProperty("type", "function");
            JsonObject clearChoiceFn = new JsonObject();
            clearChoiceFn.addProperty("name", "clear_user_choice");
            clearChoiceFn.addProperty("description", "Clear pending user choice request.");
            JsonObject clearChoiceParams = new JsonObject();
            clearChoiceParams.addProperty("type", "object");
            clearChoiceParams.add("properties", new JsonObject());
            clearChoiceParams.addProperty("additionalProperties", false);
            clearChoiceFn.add("parameters", clearChoiceParams);
            clearChoiceTool.add("function", clearChoiceFn);
            tools.add(new ToolDefinition("clear_user_choice", true, Set.of(ToolDefinition.Effect.SESSION_STATE, ToolDefinition.Effect.INTERACTION), clearChoiceTool));
        }

        {
            JsonObject projectTool = new JsonObject();
            projectTool.addProperty("type", "function");
            JsonObject projectFn = new JsonObject();
            projectFn.addProperty("name", "get_project_state");
            projectFn.addProperty("description", "Get the current project overview, workspace file list, pending paths, and summary.");
            JsonObject projectParams = new JsonObject();
            projectParams.addProperty("type", "object");
            projectParams.add("properties", new JsonObject());
            projectParams.addProperty("additionalProperties", false);
            projectFn.add("parameters", projectParams);
            projectTool.add("function", projectFn);
            tools.add(new ToolDefinition("get_project_state", false, Set.of(), projectTool));
        }

        {
            JsonObject readTool = new JsonObject();
            readTool.addProperty("type", "function");
            JsonObject readFn = new JsonObject();
            readFn.addProperty("name", "read_workspace_file");
            readFn.addProperty("description",
                    "Read a workspace file state including size, origin, revision, and TOML content. " +
                    "Returns workspace_toml, and may set truncated=true when the text is shortened.");
            JsonObject readParams = new JsonObject();
            readParams.addProperty("type", "object");
            JsonObject readProps = new JsonObject();

            JsonObject pathProp = new JsonObject();
            pathProp.addProperty("type", "string");
            pathProp.addProperty("description", "Workspace file path to read.");
            readProps.add("path", pathProp);

            JsonObject committedProp = new JsonObject();
            committedProp.addProperty("type", "boolean");
            committedProp.addProperty("description", "Defaults to true (committed script). Set false only when staged script is needed for UI diff.");
            readProps.add("committed", committedProp);

            readParams.add("properties", readProps);
            JsonArray readRequired = new JsonArray();
            readRequired.add("path");
            readParams.add("required", readRequired);
            readParams.addProperty("additionalProperties", false);
            readFn.add("parameters", readParams);
            readTool.add("function", readFn);
            tools.add(new ToolDefinition("read_workspace_file", false, Set.of(), readTool));
        }

        {
            JsonObject createTool = new JsonObject();
            createTool.addProperty("type", "function");
            JsonObject createFn = new JsonObject();
            createFn.addProperty("name", "create_workspace_file");
            createFn.addProperty("description", "Create a new workspace file in the current project.");
            JsonObject createParams = new JsonObject();
            createParams.addProperty("type", "object");
            JsonObject createProps = new JsonObject();

            JsonObject createName = new JsonObject();
            createName.addProperty("type", "string");
            createName.addProperty("description", "Display name for the workspace file.");
            createProps.add("name", createName);

            JsonObject createPath = new JsonObject();
            createPath.addProperty("type", "string");
            createPath.addProperty("description", "Logical project path, such as workspace/floors/f1.toml.");
            createProps.add("path", createPath);

            JsonObject createType = new JsonObject();
            createType.addProperty("type", "string");
            JsonArray typeEnum = new JsonArray();
            typeEnum.add("layout");
            typeEnum.add("floor");
            typeEnum.add("facade");
            typeEnum.add("component");
            typeEnum.add("semantic");
            typeEnum.add("generated");
            typeEnum.add("manual");
            typeEnum.add("shared");
            createType.add("enum", typeEnum);
            createType.addProperty("description", "Workspace file type.");
            createProps.add("type", createType);

            createParams.add("properties", createProps);
            JsonArray createRequired = new JsonArray();
            createRequired.add("name");
            createRequired.add("path");
            createRequired.add("type");
            createParams.add("required", createRequired);
            createParams.addProperty("additionalProperties", false);
            createFn.add("parameters", createParams);
            createTool.add("function", createFn);
            tools.add(new ToolDefinition("create_workspace_file", false, Set.of(ToolDefinition.Effect.PROJECT_STATE), createTool));
        }

        {
            JsonObject renameTool = new JsonObject();
            renameTool.addProperty("type", "function");
            JsonObject renameFn = new JsonObject();
            renameFn.addProperty("name", "rename_workspace_file");
            renameFn.addProperty("description", "Rename a workspace file and optionally update its logical path.");
            JsonObject renameParams = new JsonObject();
            renameParams.addProperty("type", "object");
            JsonObject renameProps = new JsonObject();

            JsonObject renamePath = new JsonObject();
            renamePath.addProperty("type", "string");
            renamePath.addProperty("description", "Current workspace file path.");
            renameProps.add("path", renamePath);

            JsonObject renameNewPath = new JsonObject();
            renameNewPath.addProperty("type", "string");
            renameNewPath.addProperty("description", "New workspace file path.");
            renameProps.add("new_path", renameNewPath);

            renameParams.add("properties", renameProps);
            JsonArray renameRequired = new JsonArray();
            renameRequired.add("path");
            renameRequired.add("new_path");
            renameParams.add("required", renameRequired);
            renameParams.addProperty("additionalProperties", false);
            renameFn.add("parameters", renameParams);
            renameTool.add("function", renameFn);
            tools.add(new ToolDefinition("rename_workspace_file", false, Set.of(ToolDefinition.Effect.PROJECT_STATE), renameTool));
        }

        {
            JsonObject deleteTool = new JsonObject();
            deleteTool.addProperty("type", "function");
            JsonObject deleteFn = new JsonObject();
            deleteFn.addProperty("name", "delete_workspace_file");
            deleteFn.addProperty("description", "Delete a workspace file if it has no pending patch and it is not the last remaining file.");
            JsonObject deleteParams = new JsonObject();
            deleteParams.addProperty("type", "object");
            JsonObject deleteProps = new JsonObject();

            JsonObject deletePath = new JsonObject();
            deletePath.addProperty("type", "string");
            deletePath.addProperty("description", "Workspace file path.");
            deleteProps.add("path", deletePath);

            deleteParams.add("properties", deleteProps);
            JsonArray deleteRequired = new JsonArray();
            deleteRequired.add("path");
            deleteParams.add("required", deleteRequired);
            deleteParams.addProperty("additionalProperties", false);
            deleteFn.add("parameters", deleteParams);
            deleteTool.add("function", deleteFn);
            tools.add(new ToolDefinition("delete_workspace_file", false, Set.of(ToolDefinition.Effect.PROJECT_STATE), deleteTool));
        }

        {
            JsonObject searchTool = new JsonObject();
            searchTool.addProperty("type", "function");
            JsonObject searchFn = new JsonObject();
            searchFn.addProperty("name", "search_block_ids");
            searchFn.addProperty("description", "Search valid block IDs by keyword or partial id.");
            JsonObject searchParams = new JsonObject();
            searchParams.addProperty("type", "object");
            JsonObject searchProps = new JsonObject();

            JsonObject searchQuery = new JsonObject();
            searchQuery.addProperty("type", "string");
            searchQuery.addProperty("description", "Keyword or partial block id to search.");
            searchProps.add("query", searchQuery);

            JsonObject searchLimit = new JsonObject();
            searchLimit.addProperty("type", "integer");
            searchLimit.addProperty("description", "Max results (1-50).");
            searchProps.add("limit", searchLimit);

            searchParams.add("properties", searchProps);
            JsonArray searchRequired = new JsonArray();
            searchRequired.add("query");
            searchParams.add("required", searchRequired);
            searchParams.addProperty("additionalProperties", false);
            searchFn.add("parameters", searchParams);
            searchTool.add("function", searchFn);
            tools.add(new ToolDefinition("search_block_ids", false, Set.of(), searchTool));
        }

        {
            JsonObject patchTool = new JsonObject();
            patchTool.addProperty("type", "function");
            JsonObject patchFn = new JsonObject();
            patchFn.addProperty("name", "propose_patch");
            patchFn.addProperty("description",
                    "Propose an editable structure patch using TOML text in patch_toml. " +
                    "The TOML body supports base_revision, intent, message_to_user, repeated [[operation]] tables, " +
                    "nested [[operation.actions_add]] / [[operation.old_actions]] / [[operation.new_actions]] tables, " +
                    "and [[operation.entry]] for palette updates.");

            JsonObject params = new JsonObject();
            params.addProperty("type", "object");
            JsonObject properties = new JsonObject();

            JsonObject patchPath = new JsonObject();
            patchPath.addProperty("type", "string");
            patchPath.addProperty("description", "Workspace file path this patch applies to.");
            properties.add("path", patchPath);

            JsonObject patchToml = new JsonObject();
            patchToml.addProperty("type", "string");
            patchToml.addProperty("description",
                    "Patch body in TOML. Top-level keys: base_revision, intent, message_to_user. " +
                    "Use [[operation]] with op=insert_part|delete_part|replace_part|insert_actions|delete_actions|replace_actions|move_actions|update_palette. " +
                    "Use insert_part for a brand-new part, and reserve insert_actions for appending to an existing part. " +
                    "For actions, use nested [[operation.actions_add]], [[operation.old_actions]], [[operation.new_actions]]. " +
                    "For palette updates, use [[operation.entry]] where omitting old_value means add-new and omitting new_value means delete.");
            properties.add("patch_toml", patchToml);

            params.add("properties", properties);
            JsonArray required = new JsonArray();
            required.add("path");
            required.add("patch_toml");
            params.add("required", required);
            params.addProperty("additionalProperties", false);
            patchFn.add("parameters", params);
            patchTool.add("function", patchFn);
            tools.add(new ToolDefinition("propose_patch", false, Set.of(ToolDefinition.Effect.PROJECT_STATE, ToolDefinition.Effect.INTERACTION), patchTool));
        }

        {
            JsonObject listSubagentsTool = new JsonObject();
            listSubagentsTool.addProperty("type", "function");
            JsonObject listSubagentsFn = new JsonObject();
            listSubagentsFn.addProperty("name", "list_subagents");
            listSubagentsFn.addProperty("description", "List all subagents created in this session.");
            JsonObject listSubagentsParams = new JsonObject();
            listSubagentsParams.addProperty("type", "object");
            JsonObject listSubagentsProps = new JsonObject();
            JsonObject includeDeleted = new JsonObject();
            includeDeleted.addProperty("type", "boolean");
            includeDeleted.addProperty("description", "Whether deleted subagents should be included.");
            listSubagentsProps.add("include_deleted", includeDeleted);
            listSubagentsParams.add("properties", listSubagentsProps);
            listSubagentsParams.addProperty("additionalProperties", false);
            listSubagentsFn.add("parameters", listSubagentsParams);
            listSubagentsTool.add("function", listSubagentsFn);
            tools.add(new ToolDefinition("list_subagents", true, Set.of(), listSubagentsTool));
        }

        {
            JsonObject createSubagentTool = new JsonObject();
            createSubagentTool.addProperty("type", "function");
            JsonObject createSubagentFn = new JsonObject();
            createSubagentFn.addProperty("name", "create_subagent");
            createSubagentFn.addProperty("description", "Create and run an asynchronous subagent task using a profile.");
            JsonObject createSubagentParams = new JsonObject();
            createSubagentParams.addProperty("type", "object");
            JsonObject createSubagentProps = new JsonObject();
            JsonObject task = new JsonObject();
            task.addProperty("type", "string");
            task.addProperty("description", "Task description passed to the subagent.");
            createSubagentProps.add("task", task);
            JsonObject profileId = new JsonObject();
            profileId.addProperty("type", "string");
            profileId.addProperty("description", "Optional profile id. Defaults to general-planner.");
            createSubagentProps.add("profile_id", profileId);
            JsonObject skillIds = new JsonObject();
            skillIds.addProperty("type", "array");
            JsonObject skillIdItem = new JsonObject();
            skillIdItem.addProperty("type", "string");
            skillIds.add("items", skillIdItem);
            skillIds.addProperty("description", "Optional scoped skill ids.");
            createSubagentProps.add("skill_ids", skillIds);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("type", "object");
            metadata.addProperty("description", "Optional metadata forwarded to subagent context.");
            createSubagentProps.add("metadata", metadata);
            createSubagentParams.add("properties", createSubagentProps);
            JsonArray createSubagentRequired = new JsonArray();
            createSubagentRequired.add("task");
            createSubagentParams.add("required", createSubagentRequired);
            createSubagentFn.add("parameters", createSubagentParams);
            createSubagentTool.add("function", createSubagentFn);
            tools.add(new ToolDefinition("create_subagent", true, Set.of(ToolDefinition.Effect.SUBAGENT_STATE), createSubagentTool));
        }

        {
            JsonObject continueSubagentTool = new JsonObject();
            continueSubagentTool.addProperty("type", "function");
            JsonObject continueSubagentFn = new JsonObject();
            continueSubagentFn.addProperty("name", "continue_subagent");
            continueSubagentFn.addProperty("description", "Continue a failed/completed/cancelled subagent with optional new instruction.");
            JsonObject continueSubagentParams = new JsonObject();
            continueSubagentParams.addProperty("type", "object");
            JsonObject continueSubagentProps = new JsonObject();
            JsonObject subagentId = new JsonObject();
            subagentId.addProperty("type", "string");
            subagentId.addProperty("description", "Subagent id to continue.");
            continueSubagentProps.add("id", subagentId);
            JsonObject instruction = new JsonObject();
            instruction.addProperty("type", "string");
            instruction.addProperty("description", "Optional follow-up instruction for the continued run.");
            continueSubagentProps.add("instruction", instruction);
            continueSubagentParams.add("properties", continueSubagentProps);
            JsonArray continueSubagentRequired = new JsonArray();
            continueSubagentRequired.add("id");
            continueSubagentParams.add("required", continueSubagentRequired);
            continueSubagentParams.addProperty("additionalProperties", false);
            continueSubagentFn.add("parameters", continueSubagentParams);
            continueSubagentTool.add("function", continueSubagentFn);
            tools.add(new ToolDefinition("continue_subagent", true, Set.of(ToolDefinition.Effect.SUBAGENT_STATE), continueSubagentTool));
        }

        {
            JsonObject getSubagentTool = new JsonObject();
            getSubagentTool.addProperty("type", "function");
            JsonObject getSubagentFn = new JsonObject();
            getSubagentFn.addProperty("name", "get_subagent");
            getSubagentFn.addProperty("description", "Get full status and result of a subagent by id.");
            JsonObject getSubagentParams = new JsonObject();
            getSubagentParams.addProperty("type", "object");
            JsonObject getSubagentProps = new JsonObject();
            JsonObject subagentId = new JsonObject();
            subagentId.addProperty("type", "string");
            subagentId.addProperty("description", "Subagent id returned by create_subagent.");
            getSubagentProps.add("id", subagentId);
            getSubagentParams.add("properties", getSubagentProps);
            JsonArray getSubagentRequired = new JsonArray();
            getSubagentRequired.add("id");
            getSubagentParams.add("required", getSubagentRequired);
            getSubagentFn.add("parameters", getSubagentParams);
            getSubagentTool.add("function", getSubagentFn);
            tools.add(new ToolDefinition("get_subagent", true, Set.of(), getSubagentTool));
        }

        {
            JsonObject deleteSubagentTool = new JsonObject();
            deleteSubagentTool.addProperty("type", "function");
            JsonObject deleteSubagentFn = new JsonObject();
            deleteSubagentFn.addProperty("name", "delete_subagent");
            deleteSubagentFn.addProperty("description", "Delete a subagent by id.");
            JsonObject deleteSubagentParams = new JsonObject();
            deleteSubagentParams.addProperty("type", "object");
            JsonObject deleteSubagentProps = new JsonObject();
            JsonObject subagentId = new JsonObject();
            subagentId.addProperty("type", "string");
            subagentId.addProperty("description", "Subagent id returned by create_subagent.");
            deleteSubagentProps.add("id", subagentId);
            deleteSubagentParams.add("properties", deleteSubagentProps);
            JsonArray deleteSubagentRequired = new JsonArray();
            deleteSubagentRequired.add("id");
            deleteSubagentParams.add("required", deleteSubagentRequired);
            deleteSubagentFn.add("parameters", deleteSubagentParams);
            deleteSubagentTool.add("function", deleteSubagentFn);
            tools.add(new ToolDefinition("delete_subagent", true, Set.of(ToolDefinition.Effect.SUBAGENT_STATE), deleteSubagentTool));
        }

        {
            JsonObject listProfilesTool = new JsonObject();
            listProfilesTool.addProperty("type", "function");
            JsonObject listProfilesFn = new JsonObject();
            listProfilesFn.addProperty("name", "list_profiles");
            listProfilesFn.addProperty("description", "List available subagent profiles.");
            JsonObject listProfilesParams = new JsonObject();
            listProfilesParams.addProperty("type", "object");
            listProfilesParams.add("properties", new JsonObject());
            listProfilesParams.addProperty("additionalProperties", false);
            listProfilesFn.add("parameters", listProfilesParams);
            listProfilesTool.add("function", listProfilesFn);
            tools.add(new ToolDefinition("list_profiles", true, Set.of(), listProfilesTool));
        }

        {
            JsonObject getProfileTool = new JsonObject();
            getProfileTool.addProperty("type", "function");
            JsonObject getProfileFn = new JsonObject();
            getProfileFn.addProperty("name", "get_profile");
            getProfileFn.addProperty("description", "Read one subagent profile by id.");
            JsonObject getProfileParams = new JsonObject();
            getProfileParams.addProperty("type", "object");
            JsonObject getProfileProps = new JsonObject();
            JsonObject profileId = new JsonObject();
            profileId.addProperty("type", "string");
            profileId.addProperty("description", "Subagent profile id.");
            getProfileProps.add("id", profileId);
            getProfileParams.add("properties", getProfileProps);
            JsonArray getProfileRequired = new JsonArray();
            getProfileRequired.add("id");
            getProfileParams.add("required", getProfileRequired);
            getProfileFn.add("parameters", getProfileParams);
            getProfileTool.add("function", getProfileFn);
            tools.add(new ToolDefinition("get_profile", true, Set.of(), getProfileTool));
        }

        return List.copyOf(tools);
    }

}
