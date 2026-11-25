# CHANGELOG

## 2026-11-26 原生 LLM 连接

- LLM 设置可选择 Chat Completions、Responses、Anthropic Messages、Gemini、Vertex、Bedrock Converse 或 Mistral Chat，并设置思考等级与可选温度。子任务和压缩沿用主连接。
- 会话保存协议所需的工具关联信息及签名。恢复同一连接和模型的会话后可继续工具循环。旧会话继续读取。
- Bedrock 支持区域端点、SigV4 和临时会话令牌。Vertex 可使用自行管理的访问令牌。

## 2026-11-25 LLM 请求参数
- 不再在每个请求里写死 `temperature: 0.4`。开了思考的 Claude（例如经 New API 网关）因此不再返回 400。需要固定温度时，在 `config/p2s_client.json` 写顶层 `"temperature"`。
- 可选顶层 `"maxTokens"`：OpenAI 官方地址发 `max_completion_tokens`，其他兼容服务发 `max_tokens`；不写就不发。
- 模型返回的思考文本（`reasoning_content`）随会话一起保存；DeepSeek 地址下会按其要求在每条助手消息里回传。

## 2026-11-25 工具 v2 与脚本
- Agent 默认改用 v2 工具集：统一的虚拟文件系统（`workspace/`、`skills/`、`agents/`）加 `ls` `read` `grep` `find` `edit` `write` `set_part` `move` `delete` `project_info` `inspect_world` `ask_user` `task` 等工具；`config/p2s_client.json` 中 `"toolset": "legacy"` 可切回原 21 个工具。
- 新增 `script`：在沙箱中运行 JavaScript，可批量调用工具、只返回结果，也可用 `ws` / `geo` 程序化编辑工作区（穹顶、球体、拱、螺旋等）；脚本失败不留下任何改动。
- 新增 `sh`：类 bash 命令行（管道、`grep`、`sed -i`、`diff` 等）直接批处理工作区文本，任一步失败则不写入。
- 新增 `inspect_world`：读取项目范围内世界里实际的方块。
- 所有修改先暂存，一轮结束后统一审批；还有文件待审批时 Agent 不会继续，全部处理后一次性收到结果。Shift+点击应用可一次应用全部。
- 其他模组可以通过 `p2s-tools` 入口注册工具。
- 大工作区文件不再受单个数据包大小限制。

## 2026-11-25 Agent 重构：换用 pi-agent-core 结构的核心
- 主 Agent 和子 Agent 改由同一个移植自 pi-agent-core 的核心驱动（`Agent` / `AgentLoop` / 事件流 / 工具钩子 / 插话与排队队列），原手写主循环和 `LLMService` 删除。
- 聊天面板新增停止：Agent 运行且输入框为空时，发送按钮变为 `■`。停止会中断模型请求和未开始的工具，已发往服务端的操作可能已生效。
- Agent 运行中也能继续发消息，消息在当前步骤结束后送达。
- Apply / Discard 改为等待服务端回执，只有确认提交后才告诉模型“已应用”；补丁在审阅后被改过时拒绝应用。
- 流式解析按 SSE 规范分帧；流中断、输出被截断或参数不完整的工具调用不再执行，而是报错给模型。
- 发给模型的上下文由完整会话记录按需生成，工具调用与结果始终成对；压缩返回空摘要时保留原历史。
- 子任务完成后自动通知主 Agent，不再需要反复轮询。
- 新增核心循环、传输解析、上下文构造和取消信号的回归测试。

## 2026-11-20 Agent 重构：工具目录与执行调度
- 将 21 个工具的协议定义和副作用元数据集中到共享工具目录，LLM 传输层不再内嵌工具 schema。
- 主 Agent 和子 Agent 通过显式工具注册表执行调用，保留各自的处理逻辑和 profile 权限限制。
- 修正批次调度顺序：只并行执行连续的只读调用，写操作、计划更新和交互工具按模型原顺序形成屏障，避免读请求跨越写入读取旧状态。
- 两类 Agent 共用有界工具执行池；取消、异常和队列满均保留逐条调用结果，重复调用不再因 Map 键相同而覆盖结果。
- 增加旧协议快照对照和工具执行回归测试。

## 2026-11-19 Agent 重构：运行边界与会话快照
- 为每轮客户端 Agent 执行引入独立运行身份，在退服、项目切换和会话关闭后拒绝旧流式回调、工具请求与 UI 更新。
- 将运行结束标记放到最后一批客户端更新之后，避免最后回复尚未显示就提前进入空闲状态。
- 会话保存改为客户端线程上的一致快照，并通过单一写入队列按顺序存盘，避免旧会话混入新项目的聊天、计划或选项。
- 关闭会话时停止关联子任务的后续工具派发，并清理断线后的待处理工具请求。
- 增加共享 JUnit 5 回归测试，覆盖两个受支持的 Minecraft 版本。

## 2026-03-09 Patch 工具语义对齐
- 关闭中间编辑区后不再切到 gameplay input，避免界面保持收起态时无法重新操作。
- 让聊天界面在重新打开时保留左目录 / 中编辑区 / 右会话栏的开启或关闭状态。
- 修复聊天界面中间编辑区关闭按钮与“获取”按钮的重叠点击区域。
- 清理 TOML patch 重构残留：移除 `upsert_part` 兼容路径，并恢复严格的 `insert_part` / `insert_actions` 语义分工。
- 批量修正内置 skills / profiles 的 patch 示例与提示词，要求“新建 part 用 `insert_part`，已有 part 增量修改用 `insert_actions`”。

## 2025-11-30 基础版本落地
- 完成 Fabric 模组初始化：工程骨架、构建/CI、主入口、命令系统、LLM 调用、结构构建与存档能力。
- 配置与 Prompt 管理体系成型：支持多 prompt、active prompt 切换、`/p2sprompt` 管理命令，以及配置持久化。
- 存储格式升级：由原始字符串内容转为结构化 JSON 存档，并兼容旧格式读取。
- 结构动作能力增强：新增 `facing` 方向处理。
- 文档与发布流程补齐：README 扩展为完整使用说明，新增基于 tag 的发布工作流。

## 2026-02-25 ~ 2026-02-26 Agent 原型阶段
- 多次 WIP 快照后，落地 agent tool loop 与结构摘要 UI，形成后续会话式工作流基础。

## 2026-02-27 会话与 Patch 稳定化
- 增加会话配置 UI 与基于物品的选区交互。
- 修复 patch 编排校验与自动应用行为。
- 加强 patch 校验与方块 ID 处理的健壮性。
- 整理并提交阶段性工作区改动。

## 2026-02-28 客户端能力全面增强
- 建立客户端 skill-agent 工作流，并完善镜像构建配置。
- 引入异步 subagent 委派与 profile 化工具控制。
- 将客户端 skills 改为全局共享。
- 默认 skills/profiles 外置化，并补充 subagent 编排指导。
- 新增显式 todo 规划与用户选项审批流（user-choice）。

## 2026-03-02 体验与可靠性强化
- 配置界面整合为单页签式 `P2SConfigScreen`。
- 支持流式输出、并行工具调用、会话持久化，并放宽循环限制策略。
- 增加 DEBUG 开关、带理由的 discard 交互、按 turn 的历史裁剪。
- 持久化空间状态，并在活跃会话中锁定选区，降低误操作风险。

## 2026-03-03 工作流深度增强与名称
- 模组名称升级为 `P2S Workspace`。
- 聊天 UI 新增可编辑 JSON 上下文面板，并支持按行区间注入上下文。
- Patch 安全性增强：由盲写操作改为严格 `old_actions` 校验。
- Subagent 支持 `continue_subagent`，可恢复失败/完成任务继续执行。
- `read_workspace_state` 增加 part 过滤和行区间读取能力。
- 补充 `.gitignore` 调整。
- 动作模型断代重构：由 `fill/frame/set` 全量切换为 `box/plane/line/points`。
- 新增基础几何表达：`box.mode=solid|shell|walls`、`plane.axis=x|y|z` + `plane.mode=solid|outline`。
- Builder / LLM schema / PatchValidator / PatchEngine / Session 统计与摘要全链路同步到新动作协议。
- 旧动作类型在执行与校验阶段均强制报错并给出迁移提示，不再兼容旧脚本。
- 默认 `default-builder` skill 吸收并转换了外部项目的组件化思路：加入 `墙/空心墙/线/线框/实心框架` 等可组合模板，并统一到本项目单文件 `SKILL.md` 格式与新动作协议。
- 默认 skill 体系拆分为“总控 + 专项”：`default-builder` 负责协议与分流，新增 `size-planner`、`style-knowledge`、`interior-furniture` 分别承载尺寸、风格、内饰桌椅规则，并注册到 `SkillStore` 默认模板。
- 新增 `read_subdoc` 工具：支持按 skill 内相对路径读取子文档；`read_skill` 现返回可读 `subdocs` 列表，`search_skill` 也会检索子文档内容。
- 新增默认技能 `component-library`，并移植外部项目的核心组件示例到 `subdocs`（墙体、框架、门窗、屋顶、楼梯、曲线、几何体、散布）且统一为 `box/plane/line/points` 模式。

## 2026-03-04 上下文编辑器 Tab 化 & Script 同步 & Info 浮层
- **上下文编辑器 Tab 化**：左侧 JSON 编辑器由单一文件模式重构为 `State / Script / Diff` 三 Tab，各自独立维护编辑状态（光标、滚动、内容），Tab 切换时自动保存与恢复。
- **Script Tab & 脚本同步**：新增 `Script` Tab，可通过 `Fetch` 按钮从服务端异步拉取当前 `VbsScriptV2` 脚本；脚本 JSON 随会话 start payload 发送至服务端并在服务端反序列化恢复，实现客户端-服务端脚本状态双向同步。
- **网络协议扩展**：`S2CSessionSyncPayload` / `C2SSessionActionPayload` 增加 `currentScriptJson` 字段，payload 最大长度由 8 KB 提升至 64 KB，支持完整脚本传输。
- **会话持久化增强**：`SessionPersistence` 的 `SavedSession` record 新增 `currentScriptJson`，脚本随会话存盘/读盘自动保存与恢复。
- **Info 浮层 (Overlay)**：消息区上方新增 `[i]` 按钮，点击弹出可滚动半透明浮层，集中显示 Action Required / Pending Patch / Structure Summary / Todo / Checkpoints 等信息段，取代之前占据固定空间的内联渲染方式，大幅释放消息区可用高度。
- **长按选区快速附加上下文**：编辑器内鼠标拖选超过 400 ms 自动进入“长按上下文模式”，松手后直接将选区文本作为上下文添加到队列，选中高亮色由蓝色变为绿色以提供视觉反馈。
- **Diff Tab 控件精简**：Diff 相关操作（Refresh / <D / D>）仅在 Diff Tab 内显示，其他 Tab 不再展示无关按钮；移除 `contextFileInput` 文本框与 `normalizeContextFileName` 方法。

## 2026-03-06 项目 / 工作区 API 与多文档模型
- 引入 M1 项目工作区 API：新增 `list_projects`、`create_project`、`open_project`、`rename_project`、`get_project_state` 等接口。
- 会话模型从单文档改为 **multi-document workspace model**，每个项目可维护多份工作区文件与待处理补丁。
- `read_workspace_state` 精简返回体，并默认读取 committed 版本，降低网络与上下文体积。

## 2026-03-07 项目状态解耦、界面重构与 i18n 补齐
- 将项目状态与会话状态彻底解耦，项目切换与会话恢复逻辑更清晰。
- 新增 `zh_cn` / `en_us` 语言文件，UI、提示、反馈文本全面转向 i18n。
- 聊天界面重构并恢复工作区状态，拆出 `screen` / `screen.chat` / `screen.widget` / `store` 等客户端结构。
- 新增项目列表、工作区浏览器、检查点列表等界面，并继续打磨检查点 / 工作区交互。
- 技能与系统提示改为多行编辑器，提升长文本编辑体验。
- 上下文编辑器的选区与注入体验继续优化。

## 2026-03-08 客户端桥接、上下文压缩与工具日志打磨
- Agent 流程迁移到客户端桥接层，恢复 choice UI，并补齐客户端/服务端双端安装检测。
- 引入本地上下文压缩（local client context compaction），并把相关阈值与提示词暴露到配置界面。
- 聊天 UI、工作区浏览器、检查点界面继续打磨，提升恢复、浏览与选择体验。
- 工具日志支持折叠显示，并限制 session sync 文本长度，降低面板噪声。
- 聊天 explorer 与工具日志表现进一步整理，为后续 TOML 工作流收口铺路。

## 2026-03-09 TOML 工作流收口与面板交互定型
- 工作区正文存储正式切换到 TOML，新增 `WorkspaceTomlCodec` / `PatchTomlCodec` 路径并统一 `workspace_toml` / `patch_toml` 协议。
- 默认 prompts、skills 与 patch 指南同步迁移到 TOML 语义，相关默认 skill 示例也随之更新。
- 清理 TOML 工作区编辑链路，减少旧 JSON 管线遗留逻辑。
- 聊天编辑区支持在游戏内折叠停靠，减轻大面板对视野的遮挡。
- 计划跟踪逻辑统一收束到 `update_plan`，替换早期 todo 口径。
- 移除服务端配置残留：删除 `/p2sreload` 与 `p2s.json` 路径，把 patch/超时等运行参数并回 `p2s_client.json`。

