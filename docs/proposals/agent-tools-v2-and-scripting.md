# Agent 工具 v2 与脚本能力

> 状态：已实施（2026-11-25），阶段 A–C 完成，阶段 D 未做。实施中的调整（Rhino 修正层、`batch` 取代 `parallel`、审批汇总）见 `docs/architecture/agent-runtime-migration.md`。

## 1. 目标

原有 21 个工具粒度碎、改建筑只能手写补丁格式、看不到世界、加工具要改三处代码。参照 pi（pi-coding-agent 1.6.0）的思路改为：

- 少而通用的工具，统一操作一套虚拟文件系统；
- 两类脚本：**编排脚本**（对应 pi 的 `codemode`，在脚本里调用工具，减少轮次和上下文）和**编辑脚本**（对应 pi 的 `bash`：类 bash 文本批处理 `sh`，以及 JS 程序化编辑）；
- 所有修改先进暂存区，一轮结束后由玩家统一审批，世界只在 Apply 后改变。

旧工具集保留为 `toolset = "legacy"`，新工具集为 `"v2"`（默认），用于对比。

## 2. 虚拟文件系统

| 路径 | 内容 | 权限 |
|---|---|---|
| `workspace/<path>.toml` | 工作区文件（读默认取暂存版） | 读写，写入只进暂存区 |
| `skills/<id>/SKILL.md`、`skills/<id>/subdocs/...` | 技能 | 只读 |
| `agents/<profile>.json` | 子 Agent profile | 只读 |

`..`、绝对路径、其他根目录一律拒绝。

## 3. 暂存与审批

- 服务端新增 `stage_workspace_file(path, workspace_toml, base_revision?)`：解析 TOML → 与已提交版按 part 对比 → 合成补丁（`insert_part` / `replace_part` / `delete_part` / `update_palette`）→ 走现有校验与预览 → 更新该文件的待审批补丁，返回 `staged_revision`。
- `base_revision` 与当前暂存版本不一致时拒绝，防止主/子 Agent 互相覆盖。
- v2 下修改不暂停本轮；本轮结束后列出全部待审批文件，支持“全部应用 / 全部丢弃”，也可以逐个处理。应用仍以服务端回执为准。
- `[workspace]` 头部（名称、类型、原点、尺寸）的修改不经暂存，`stage` 时忽略并给出警告；移动或缩放工作区请用游戏内界面。
- 移动、删除工作区文件立即生效（不进暂存区），与原工具相同。

## 4. 工具清单（v2）

| 工具 | 参数 | 说明 |
|---|---|---|
| `ls` | `path` | 列目录 |
| `read` | `path, offset?, limit?, committed?` | 带行号读文本；默认 400 行，提示下一段起始行 |
| `grep` | `pattern, path?, ignore_case?, literal?, limit?` | 递归搜索，返回 `文件:行号:内容` |
| `find` | `pattern, path?` | 按文件名通配查找 |
| `edit` | `path, edits:[{old_text,new_text}]` | 原文替换，原文必须唯一，全部成功才写入 |
| `write` | `path, content, type?` | 新建或整份覆盖工作区文件 |
| `set_part` | `path, part, actions, mode=replace\|append, priority?` | 结构化写入一个 part |
| `move` / `delete` | `path, new_path` / `path` | 工作区文件改名、删除 |
| `project_info` | — | 项目概况、文件列表、待审批路径、选区 |
| `inspect_world` | `from, to`（世界坐标）或 `path` | 读取实际方块；体积上限 32768，超出只返回统计 |
| `search_block_ids` | `query, limit?` | 不变 |
| `ask_user` | `prompt, options` | 暂停本轮等待玩家选择 |
| `update_plan` | `explanation?, plan` | 不变 |
| `task` | `task, profile_id?, skill_ids?, id?, instruction?` | 新建子任务；带 `id` 时继续 |
| `task_status` | `id?` | 查单个或列出全部 |
| `task_cancel` | `id` | 取消并删除 |
| `script` | `source` | JS 脚本，见 §5 |
| `sh` | `command` | 类 bash 命令行，见 §6 |

子 Agent 的 profile 仍按工具名白名单限制；旧工具名自动映射到 v2 对应工具。

## 5. `script`：JS 脚本（编排 + 程序化编辑）

### 5.1 运行环境

- 引擎：Rhino 1.9.2，解释模式，`initSafeStandardObjects`，禁止访问一切 Java 类。
- 执行前的源码修正（调研发现 Rhino 有静默错误，必须处理）：
  - `const` 改写为 `let`（Rhino 中循环体内的 `const` 只初始化一次，`for (const x of …)` 在函数内报语法错误）；
  - 去掉 `async` / `await` 关键字（同步 API）；
  - 循环头 `let` 变量被循环体内的闭包引用时，改写为循环体内重新声明（Rhino 中循环头变量不按迭代新建）；循环体同时修改该变量时拒绝执行并说明原因。
- 不支持：`class`、`async` 生成器、调用处展开参数 `f(...xs)`（报语法错误，用 `apply`）。
- 第一行可写 `// @options: {"timeout_ms": 30000, "max_output_chars": 40000}`。

### 5.2 全局对象

| 名字 | 说明 |
|---|---|
| `tools.<name>(args)` | 调用当前 Agent 允许的工具，同步返回结果对象；失败抛出 `Error`。`script`、`sh`、`ask_user` 不可调用 |
| `batch([{tool, args}, ...])` | 并发执行一组调用（只读工具并行、有副作用的按顺序），返回 `[{ok, result \| error}]` |
| `text(x)`、`return x`、`console.log(...)` | 输出 |
| `store(key, value)` / `load(key)` | 跨脚本保存少量 JSON，脚本成功才写入，随会话存盘 |
| `ALL_TOOLS`、`describeTool(name)` | 查询可调用工具 |
| `fs.read(path)`、`fs.write(path, text)`、`fs.list(dir)`、`fs.exists(path)` | 文本读写虚拟文件系统 |
| `ws.load(path)` / `ws.save(path, doc)` | 工作区对象：`{workspace, palette, parts:[{name, priority, actions:[...]}]}` |
| `geo.*` | 几何生成与变换，返回动作数组，见 §5.4 |
| `Promise.all` / `Promise.allSettled` | 兼容写法，按顺序执行；需要并发请用 `batch` |

### 5.3 事务

`fs.write` / `ws.save` 写入脚本内的副本，脚本成功结束时逐个文件提交到暂存区；脚本失败、超时或被停止时一律不提交。通过 `tools.*` 调用的修改类工具调用即暂存，不在事务内。

### 5.4 `geo`

坐标为工作区相对坐标。形状：`circle`、`disk`、`sphere`、`dome`、`cylinder`、`cone`、`torus`、`spiral`、`bezier`、`arch`；变换：`translate`、`rotateY`、`mirror`、`scale`；工具：`dedupe`、`voxels`、`fromVoxels`。生成结果会把同一行相邻方块合并为 `line`，其余合并为一个 `points` 动作。

### 5.5 限制

| 项目 | 值 |
|---|---|
| 超时 | 默认 30 秒，最多 120 秒 |
| 子调用 | 每个脚本 256 次 |
| 输出 | 40000 字符，保留首尾 |
| 动作数 | 单个脚本生成 20000 个 |
| `store` | 单值 256KB，总计 1MB |
| Stop | 立即中断，不提交 |

结果中附带子调用记录（工具名、成功或失败），界面折叠显示。

## 6. `sh`：类 bash 文本批处理

- 只作用于虚拟文件系统，不访问操作系统。
- 语法：管道、`&&`、`||`、`;`、`>`、`>>`、`<`、单双引号与转义、通配符；不支持变量、命令替换、循环、后台任务。
- 命令：`ls cat head tail wc grep find sed sort uniq cut tr cp mv rm tee echo printf diff tomlfmt`。`sed` 支持 `s///[gi]`、`p`、`d`、行号与正则地址、`-n`、`-i`、`-E`；正则使用 Java 语法，BRE 写法自动转换。
- 写入在整条命令成功后提交，任一步失败则全部不提交。单条命令超时 10 秒，管道数据上限 4MB。

## 7. 传输限制

Minecraft 客户端发往服务端的自定义包上限约 32KB。工具桥参数超过 8000 字符时自动分片上传、服务端重组（单次上限 1MB）；服务端回包上限提高到 262144 字符。

## 8. 分阶段

| 阶段 | 内容 |
|---|---|
| A | 虚拟文件系统、暂存接口、`ls/read/grep/find/edit/write/set_part/move/delete/project_info`、统一审批、`toolset` 开关、传输分片 |
| B | `script`（Rhino 沙箱、源码修正、`tools/batch/store/fs/ws/geo`）、`sh` |
| C | `inspect_world`、`task*`、profile 映射、扩展入口、技能文档更新、协议快照 |
| D（可选） | 脚本存为生成器、技能附带脚本 |
