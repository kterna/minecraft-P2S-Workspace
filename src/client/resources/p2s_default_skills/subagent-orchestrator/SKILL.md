---
name: "Subagent 调度指南"
description: "为独立的规划、配色或工作区改动选择子代理，分配文件并等待结果。"
---

# 子代理调度

独立任务可以并行派发。请交代目标、尺寸与风格、允许改动的文件以及验收条件。

在 v2 工具集中：

1. 通过 `ls agents` 和 `read agents/<profile>.json` 查看各 profile 的 `effective_tools`。通用规划与配色助手只读，要编辑文件可选 `patch-planner`。
2. 调用 `task` 时指定 `task`、`profile_id` 和可选的 `skill_ids`。需要编辑时，用 `owned_paths` 明确指定现有或拟新增的工作区文件，例如 `["workspace/hall.toml"]`。创建结果会列出该子代理实际可用的工具。
3. 主代理可继续处理独立的工作。需要使用委派的结果时，调用 `task_status`，传入 `id` 和 `wait: true`。单次等待最多 30 秒，返回 `timed_out: true` 时任务仍在进行。保留文件直到子代理完成，不要重复生成它负责的部分。
4. 收到完成通知后读取结果。需要补充工作时用 `task` 传入原 `id` 和新 `instruction`，取消时调用 `task_cancel`。

在旧工具集中，分别使用 `list_profiles`、`get_profile`、`create_subagent`、`get_subagent`、`continue_subagent` 和 `delete_subagent`。子代理不能继续派发子代理。
