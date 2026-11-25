# Agent 核心渐进迁移

目标：用 pi-agent-core（`@earendil-works/pi-agent-core`）的 harness 设计替换原来手写的 Agent 主循环，同时保持纯 Java 21、单 JAR，不引入 Node 进程。

`docs/proposals/agent-core-pi-architecture-refactor.md` 是最初的提案。评审后实际执行的是下面七批方案，与提案的主要差别：

- 审批不靠一个挂起的内存 Future 等玩家。`propose_patch` / `request_user_choice` 成功后本轮暂停，玩家的决定作为下一轮输入；Apply / Discard 以服务端回执为准。
- 不自动修补截断的工具参数。参数不完整的调用只报错给模型，不执行。
- 取消的验收标准是“立即停止后续调度”，不承诺撤销已发往服务端的操作。

| 批次 | 内容 | 状态 |
|---|---|---|
| 1 | 运行身份、回调归属、会话快照 | 完成 |
| 2 | 工具目录、执行接口、调度顺序 | 完成 |
| 3 | 模型传输拆分（SSE 分帧、工具参数组装、HTTP 取消） | 完成 |
| 4 | 会话记录与模型上下文分离 | 完成 |
| 5 | 审批回执与暂停 | 完成 |
| 6 | pi 风格 `Agent` 替换主循环，接入 Stop / 插话 / 排队 | 完成 |
| 7 | 子 Agent 复用核心，完成后通知父 Agent | 完成 |

## 第一批：运行归属和一致快照

- `AgentRunGuard` 为每轮执行生成 session ID、连接代次和唯一 run ID。关闭会话或完成运行后，旧回调失效；恢复同一存档 ID 不会复用运行身份。
- `ClientAgentManager` 继续作为现有界面入口，所有后台 UI 回调在客户端真正执行时重新检查运行和项目归属。运行完成标记排在末尾，避免提前接受下一轮输入。
- `ClientToolBridge` 在实际发包前及响应消费前检查请求有效性；退服时完成并清理等待中的工具 Future。已发出的服务端操作不因本地失效而自动撤销。
- 主 Agent 的 assistant/tool 结果成组提交到历史，避免在工具批次尚未完成时保存半组历史。
- `ClientSessionState.captureSessionView()` 一次捕获聊天、计划、选项、项目和路径。保存只使用当前会话的快照，项目已切换时使用之前捕获的本会话快照。存盘按捕获顺序串行执行。
- 主会话关闭或退服时，关联子任务停止新的工具派发。

存档字段、工具名称和 Patch/TOML 格式保持兼容。第一批不提供新的 Stop/插话功能，也未替换模型传输和审批协议。

## 第二批：工具目录与执行接口

- `ToolCatalog` 独立于 Minecraft 和 LLM 传输，集中维护既有 21 个工具的声明、顺序及副作用。`ToolDefinition` 不向外暴露可变 schema。
- `ToolRegistry` 显式注册主/子 Agent 的处理函数，复制 profile 白名单，在执行前校验运行有效性。未注册、未授权、异常和空结果都转换为带调用关联的工具观察。
- `ToolBatchExecutor` 对同一模型批次只并行执行连续的只读调用；任何有副作用的工具均为顺序屏障。结果按输入位置收集，重复调用不会互相覆盖。
- 主/子 Agent 共用 4 个工作线程和最多 64 个排队工具的执行池。队列满返回明确错误，不让 Minecraft 调用线程代为执行工具。
- 新执行接口返回 Future，兼容入口暂时仍等待结果；之后迁移 P2SAgent 时可直接使用异步完成事件。
- 在修改 LLMService 前，从已编译版本离线导出工具声明到 `src/test/resources/p2s/tool-catalog/legacy-tools.json`。新目录和传输请求与旧协议快照比较，覆盖名称、参数、描述和顺序。

此批的“交互屏障”只约束批内执行顺序。玩家等待状态、服务端提交回执及跨 Agent 的工作区版本冲突控制仍按后续批次推进。

## 第三至第七批：pi 核心替换

### 核心（`com.p2s.agent.core`，只依赖 Gson）

按 pi-agent-core 的结构移植：

| pi-agent-core | 本项目 |
|---|---|
| `Agent` | `Agent`：持有会话记录，一次只跑一轮；`prompt` / `continueRun` 阻塞调用线程，`abort` / `steer` / `followUp` 可从任意线程调用 |
| `runLoop` | `AgentLoop`：轮次、工具执行、插话与排队的处理顺序与 pi 相同 |
| `AgentEvent` | `AgentEvent`：`AgentStart … AgentEnd`，`AgentEnd` 一定是最后一个事件 |
| `StreamFn` | `StreamFunction`：不抛异常，失败写进 `stopReason=ERROR/ABORTED` |
| `AbortSignal` | `AbortSignal`：注册与取消互斥，回调不会丢也不会重复执行 |
| `beforeToolCall` / `afterToolCall` / `transformContext` / `prepareNextTurn` / `shouldStopAfterTurn` | 同名钩子，见 `AgentLoopConfig` |

在 pi 基础上加的约束：

- 每个工具调用都恰好得到一个结果，顺序与模型给出的一致，然后才发下一次请求。暂停或停止时，没执行的调用补一条 `not_executed` 结果。
- 只有相邻的只读工具并行；有副作用的工具是屏障（沿用第二批规则，`ToolBatchExecutor` 因此删除，测试迁入 `AgentLoopTest`）。
- 工具结果可以带 `PAUSE`：本批剩余调用不执行，本轮以 `PAUSED` 结束。
- 停止后，仍在运行的并行工具有 1.5 秒宽限，之后丢弃其结果，Stop 不会被卡住。

### 传输（`com.p2s.agent.llm`）

- `SseDecoder` 按 SSE 规范分帧：LF / CR / CRLF、注释行、多行 `data`、冒号后可选空格；流末尾没有空行的事件也会派发。
- `ToolCallAssembler` 按 index 累加工具参数，结束时校验。参数不是完整 JSON 对象就标为无效，不修补。
- `OpenAiChatCompletions` 实现 `StreamFunction`：`AbortSignal` 触发 `Call.cancel()`；流没有正常结束（无 `finish_reason` 也无 `[DONE]`）时不返回任何工具调用；`finish_reason=length` 时工具调用全部标为无效；分离 `reasoning_content` / `reasoning` 到 thinking。
- `OpenAiMessageCodec` 负责中性消息与 OpenAI JSON 的互转。存盘格式不变，旧会话可直接恢复；参数无效的调用回传时写成 `{}`。
- `LLMService` 已删除。

### 上下文（`com.p2s.agent.context.ContextBuilder`）

- 会话记录保存完整内容（上限 400 条）；每次请求由 `transformContext` 生成视图（上限 40 条），不再直接截断存储的历史。
- 视图先修复调用配对：失败回复去掉工具调用；缺结果的补 `not_executed`；重复和孤立结果丢弃。再按“用户消息开头的一组”整组裁剪，压缩摘要始终保留。
- 自动压缩：轮前在空闲时执行，轮间通过 `prepareNextTurn` 执行；压缩请求可被 Stop 取消。压缩返回空摘要时放弃压缩、保留原历史（原代码这里的空值检查不会生效）。

### 审批（第五批）

- 服务端新增两个只走工具桥的动作 `session_apply_patch` / `session_discard_patch`，返回带 `revision` 的回执；模型看不到这两个名字。
- `propose_patch` 的结果新增 `pending_revision`。Apply 时带上它，补丁在此期间被重新提交过则拒绝（`stale_approval`）。
- 只有回执成功才告诉模型“已应用/已丢弃”；失败时提示玩家，补丁保持待审批。自动 Apply 走同一路径。

### Minecraft 桥接（第六批，`ClientAgentManager`）

- 每个会话一个 `Agent`；`AgentRunGuard` 继续负责运行归属，事件经运行校验后投递到客户端线程。
- Stop：发送按钮在 Agent 运行且输入框为空时变为 `■`。停止会中断模型请求、跳过未开始的工具、结束等待中的服务端请求、取消本会话子任务。回车不触发停止。
- 插话：运行中发送的消息进入 steering 队列，在当前工具批次结束后注入。运行刚结束时到达的消息会立即开启下一轮；停止后不自动消费，留到下一轮开头。
- 待审批或待选择时仍不接受普通聊天，与原行为一致。
- 安全上限仍为每轮 50 次请求，由 `shouldStopAfterTurn` 实现。

### 子 Agent（第七批，`SubagentManager`）

- 每次运行构造同一个核心 `Agent`，工具按 profile 白名单，轮数与超时按 profile；删除或取消会中断其请求。
- 结束（完成或失败）后通知父会话：父 Agent 运行中则插入其下一轮；等待玩家时排队；空闲时自动开一轮继续。系统提示改为“完成会自动通知，不要反复轮询 `get_subagent`”。

### 未做 / 已知限制

- 只实现了 OpenAI 兼容的 Chat Completions；Anthropic 原生协议、Responses API 未接入。
- thinking 内容会解析和记录，但不显示在界面，也不回传给模型（与原行为一致；DeepSeek 思考模式多轮工具调用如要求回传 `reasoning_content`，需要另做）。
- 待审批状态的数据仍分散在服务端项目（补丁）和会话存档（选项）里，没有统一成一个可恢复对象；恢复会话后 Apply 不带 `expected_revision`。
- 子 Agent 与主 Agent 对同一工作区的并发写入，仍只靠服务端的待审批补丁合并，没有工作区级锁。
- **长期记忆未合入**：远端 `master` 的 `b340844 feat: add persistent memory across compaction`（2026-03-22）不在本分支，本分支基于其之前的 `2e30f58`。它在旧主循环里往历史插入记忆系统消息，并依赖已删除的 `LLMService`，需要移植：调用改用新传输层，记忆改为每轮拼接到系统提示，保留“轮后后台更新、下一轮前等待完成”的时机。用户反馈该功能本身可能有问题，后续优化时一并处理。合入前本分支不能并入 `master`，否则会丢失该功能。
- 游戏内流程未验证。

## 验证

`./gradlew build` 在 Java 21 JDK 下构建 1.21 和 1.21.1，并运行共享 `src/test/java` 回归测试。游戏内还需核对断线重连、项目切换、会话恢复、自动 Apply 和 UI 最后一条回复的时序。

## 2026-11-19 验证记录

- 使用临时 Temurin Java 21 JDK 执行一次 `./gradlew build --no-daemon --max-workers=2 --console=plain`。
- 1.21 与 1.21.1 的 main/client 源码及测试源码均编译通过，版本目录已生成 JAR。
- 每个版本执行 9 项测试，8 项通过、1 项失败。失败位于快照测试夹具：传入所选路径但文件列表为空，现有合法路径校验因此清空选择。
- 已补齐夹具中的工作区文件列表，保留原有断言。2026-11-20 用户明确要求继续后重跑，1.21 与 1.21.1 各 9 项测试全部通过，根 `build` / `buildAndGather` 成功。
- 游戏内手动流程尚未验证。

## 第二批验证状态

2026-11-20：`./gradlew build --no-daemon --max-workers=2 --console=plain` 成功，耗时 1 分 6 秒。

- 1.21 与 1.21.1 各运行 30 项测试，失败、错误和跳过均为 0；共 60 项全部通过。
- 旧协议快照对照、主/子 Agent 注册完整性、profile 权限、调度屏障、重复调用、失效运行和队列满处理均已覆盖。
- `build/libs` 已收集两个版本的运行 JAR，确认包含新工具核心类，未打包测试类及旧协议快照。
- 游戏内手动流程仍未验证；后续继续拆分模型传输与取消机制。

## 第三至第七批验证状态

2026-11-25：`./gradlew build --no-daemon --max-workers=2 --console=plain`（临时 Temurin Java 21 JDK）一次成功，耗时 54 秒。

- 1.21 与 1.21.1 各运行 67 项测试，失败、错误和跳过均为 0；共 134 项全部通过。
- 新增覆盖：核心事件顺序、读写屏障与并行、暂停与停止时的调用闭合、卡住的工具不阻塞 Stop、插话与排队、监听器异常、取消信号竞态、SSE 分帧、流中断与截断、参数校验、旧会话格式往返、上下文配对修复与整组裁剪。
- `build/libs` 已生成两个版本的 JAR，包含 `com.p2s.agent.core` 等新类，未打包测试类及旧协议快照。
- 游戏内手动流程未验证，需要重点核对：Stop、运行中插话、Apply / Discard 回执与自动 Apply、子任务完成后自动继续、会话恢复（含旧存档）、自动压缩。

## 工具 v2 与脚本（2026-11-25）

方案见 `docs/proposals/agent-tools-v2-and-scripting.md`。配置 `config/p2s_client.json` 的 `"toolset"`：`"v2"`（默认）或 `"legacy"`（原 21 个工具，协议快照不变）。

### 结构

| 位置 | 内容 |
|---|---|
| `agent/vfs`（main） | `VirtualFs` 接口、路径规则、`OverlayFs` 事务、`TextEdits`、`WorkspaceDocs`（TOML ↔ 对象，复用 `WorkspaceTomlCodec`） |
| `agent/sh`（main） | `Shell`：类 bash 解析与执行、`PosixRegex`、`TextDiff` |
| `agent/script`（main） | `ScriptSandbox`（Rhino 1.9.2）、`ScriptSourceRewriter`、`Geo` |
| `agent/tool`（main） | `ToolCatalogV2`、`ToolExtensions`（Fabric 入口 `p2s-tools`）、`ToolRegistry`/`RegistryTools` 支持指定目录 |
| `ClientVirtualFs`、`AgentToolsV2`（client） | 虚拟文件系统的客户端实现与 v2 工具处理函数，主 Agent 与子 Agent 共用 |
| `PatchSynthesizer`、`WorldInspector`、`BridgeUploads`（main） | 整文件暂存时合成补丁、读取世界方块、工具桥分片上传 |

### 与方案的差异和实施中的发现

- **Rhino 的静默错误**：实测 1.9.2 中循环体内 `const` 只取第一次的值、循环头变量被所有迭代共用（闭包拿到最后的值）、函数内 `for (const x of …)` 报语法错误、不支持调用处展开参数。`ScriptSourceRewriter` 把 `const` 改为 `let`，在循环体内重新声明被闭包引用的循环头变量，无法等价改写时（循环体同时修改该变量）拒绝执行。改写不增加行，错误行号不变。
- **并行**：Rhino 上下文只能在一个线程运行，JS 函数不能并发，所以并行放在工具层：`batch([{tool, args}])` 并发执行相邻的只读工具；`Promise.all` 仅为兼容写法，按顺序执行。
- **数据包上限**：客户端发往服务端的自定义包约 32KB。工具桥参数超过 8000 字符时分片，服务端 `BridgeUploads` 重组（单次 1MB、每玩家 4 个、60 秒过期）；服务端回包上限由 32767 提高到 262144 字符，超限时返回错误而不是编码异常。
- **审批**：每次 Apply/Discard 只处理一个文件（Shift+Apply 或自动应用时处理全部），以服务端回执为准；仍有待审批文件时不唤醒 Agent，全部处理完后把所有决定汇总成一条消息发给模型。
- **暂存的范围**：`[workspace]` 头部（原点、尺寸、类型）的修改不进暂存区，返回警告；`move`/`delete` 立即生效，与原工具一致。
- **合成补丁不计操作数上限**：沿用 `maxPatchOps`（默认 20000）与方块数上限；只有空 part 或顺序变化时没有可校验的操作，直接暂存。
- **子 Agent**：profile 中的旧工具名经 `ToolCatalogV2.fromLegacy` 映射为 v2 工具；子 Agent 没有 `ask_user`、`task`、`task_cancel`；`update_plan` 改在客户端线程执行（原实现在工作线程直接修改界面状态）。
- **脚本 `store`** 随会话存档（`scriptStore` 字段，旧存档缺省为空）。
- 默认技能文档仍讲解 `propose_patch` 的补丁格式；v2 的系统提示说明这些工具不可用、改用文件工具。技能正文尚未重写。

### 工具 v2 验证状态

- 2026-11-25 第一次构建：编译通过，1.21 有 9 项测试失败。原因相同：解析 TOML 时 `WorkspaceTomlCodec` 经 `ProjectPersistence.normalizeWorkspaceType` 加载了 `ProjectPersistence`，其静态初始化需要 Fabric 配置目录，单测环境不可用（游戏内不受影响）。修复：类型校验移入 `WorkspaceTomlCodec.normalizeType`，`ProjectPersistence.normalizeWorkspaceType` 改为调用它，行为不变。
- 修复后经用户同意重跑一次 `./gradlew build --no-daemon --max-workers=2 --console=plain`，成功，耗时 41 秒。1.21 与 1.21.1 各 127 项测试，失败、错误、跳过均为 0。
- 两个版本的 JAR 已打包 `rhino-1.9.2.jar` 和新类，未包含测试类。
- 游戏内未验证。重点：v2 下的读写与暂存、多文件审批与 Shift+Apply、`script` 与 `sh` 的实际效果、`inspect_world`、大文件分片上传、子 Agent 的 v2 工具、`toolset = "legacy"` 回退。
