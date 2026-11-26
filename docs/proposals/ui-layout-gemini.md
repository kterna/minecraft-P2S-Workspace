# P2S 游戏内 Agent 界面「B+C 结合」布局与视觉方案（Gemini 方案）

> 核心思路：**平时打开就是聊天窗（B），右半边把世界让出来看预览；需要审阅多个文件或偶尔微调配置时，直接在同一个面板顶栏切标签（C），不用在三栏里找按钮。**

---

## 1. 设计出发点：为什么选「左置 B+C 单工作台」

当前 `P2SChatScreen` 是三栏铺满全屏（文件树 + TOML 编辑器 + 聊天），问题很明显：
1. **挡视野**：玩家改建筑时最需要看右侧的方块预览，三栏把游戏画面几乎盖光了。
2. **主次颠倒**：95% 的时候玩家是在“vibe coding”（说人话提需求、看进度、决定应用或丢弃），很少自己写 TOML，却一直被迫盯着大片代码编辑器和 3 排工具按钮。
3. **多文件审阅割裂**：Agent 一次改了 3 个文件，聊天里只有一句提示，玩家得跑到左侧文件树挨个点开再找 `Diff`。

因此，本方案把原来的三栏收拢为一个**左侧停靠的 B+C 一体工作台**：
- **标准桌面（`640×360` 逻辑像素，对应 1280×720 @ GUI Scale 2）**：
  - **左侧工作台固定 `352px` 宽（约 55%）**，满高 `360px`（也可以一键收起成 `20px` 贴边窄条，随时偷看完整世界）。
  - **右侧 `288px`（约 45%）完全留给游戏世界**：选区框、当前建筑、待审补丁的半透明预览方块始终可见。
- **风格坚持 MC 原生可画**：全直角（`0px` 圆角）、纯色/半透明矩形、1px 边框、1px 顶部高光线、Unifont 点阵字感（`8px` 字号 / `9px–11px` 行高），不用网页渐变或毛玻璃，在 `GuiGraphics` 里全都能用 `fill` 和 `drawString` 直接实现。

---

## 2. 信息架构（3 层结构）

整个左侧工作台从上到下只有 **3 块**，把原来的 3 排拥挤按钮压成 2 条 18px 顶栏：

```text
┌─────────────────────────────────────────────┐
│ A. 全局顶栏 (18px)                           │
│    [◈ 竹林小院 · #a4f2 ▾]  32×18×32  [撤销][重做][存点][压缩][⚙] │
├─────────────────────────────────────────────┤
│ B. B+C 模式标签栏 (18px)                     │
│    [ 对话 ]   [ 审阅 3 ]   [ 文件 ]      [◧] │
├─────────────────────────────────────────────┤
│                                             │
│ C. 当前视图主体区 (324px)                    │
│    - 对话页：计划条 + 消息流/工具卡片 + 待审汇总卡 + 底部输入/停止/排队 │
│    - 审阅页：待审文件队列 + 文件摘要/风险 + Diff + 应用/丢弃底栏 │
│    - 文件页：精简目录树 + TOML 正文 + 挂载上下文底栏 │
│                                             │
└─────────────────────────────────────────────┘
```

### 各区域职责边界
- **A. 全局顶栏（永远可见）**：
  - 左：当前项目与会话切换胶囊 `[◈ 竹林小院 · #a4f2 ▾]`（点击弹出切换/新建会话/项目管理），旁边轻量展示选区尺寸 `32×18×32`。
  - 右：高频全局操作 `[撤销]` `[重做]` `[存点]` `[压缩]` `[⚙]`。不再把“应用 / 丢弃 / 检查点改名”全塞在顶栏占位置。
- **B. B+C 模式标签栏（核心骨架）**：
  - **`对话`**：默认首页。日常发起任务、看进度、追加消息、快速确认都在这里完成。
  - **`审阅 [N]`**：一旦有待审补丁，立刻亮起琥珀色数字角标（例如 `审阅 3`）。没有待审文件时呈低对比灰态（仍可点进来看“当前无待审改动”或最近一次应用记录）。
  - **`文件`**：vibe coding 的“保留逃生舱”。平时不打扰，需要查一下坐标、改一个方块 ID 或选几行挂到对话上下文时点进来。
  - 最右 **`[◧]`**：世界透视折叠按钮，把左面板缩成 `20px` 窄条（保留状态灯与待审角标），方便玩家在世界里绕着预览走一圈。

---

## 3. 视图怎么切换（平滑、不打断对话）

1. **手动点击 / 快捷键切换**：
   - `Alt+1` / 点击 `[对话]`
   - `Alt+2` / 点击 `[审阅 N]`
   - `Alt+3` / 点击 `[文件]`
   - `Alt+H` 或点 `[◧]`：折叠/展开工作台看世界。
2. **语义联动跳转（减少来回找）**：
   - 在 `对话` 页的**待审汇总卡片**里，点击某个文件的 `[审阅]` 或 `[逐个审阅 →]` → 自动切到 `审阅` 页并定位到该文件；同时**右侧世界高亮该文件对应的方块部位**（其余待审文件降为低亮线框）。
   - 在 `审阅` 页发现某个细节想手改（比如把墙高多加 1 格），点击 `[在文件页编辑 ↗]` → 自动切到 `文件` 页并打开对应 TOML。
   - 在 `审阅` 页把最后一个待审文件点完（应用或丢弃）→ 出现“全部 3 个文件已处理，已通知 Agent”轻提示 + `[回到对话]` 按钮（也可在设置里勾选“审阅完毕自动回对话”）。
3. **不会强行抢焦点跳走**：
   - Agent 跑完生成 3 个待审文件时，**停留在 `对话` 页**，在消息流底部滚出清晰的「待审改动卡片」+ 顶栏 `审阅 3` 角标高亮闪烁一次；不突然把玩家硬切走，防止玩家正好在看回复或刚想打字时画面突变。

---

## 4. 一个完整任务怎么走（端到端流程）

### 第 1 步：发起任务（`对话` 页）
- 玩家用选区物品圈好 `32×18×32` 的空地，按 `O` 打开面板，直接落在 `对话` 页。
- 输入一句话：`"在选区里建一个带飞檐和庭院灯笼的竹林茶室"`，按 `Enter`（或点 `>` 发送）。

### 第 2 步：运行中、看进度、停止与排队追加（`01-chat-running.html`）
- **Plan 进度条**：Agent 调用 `update_plan` 后，标签栏下方吸顶一条紧凑计划栏：`计划 2/4 · 搭建主体墙面与屋顶 [▾]`，点开可看 4 步清单。
- **工具与子代理卡片**：消息流里直接以折叠块展示当前动作：
  - `✓ inspect_world · 读取地形与边界 (14ms)`（可点击展开参数详情）
  - `▶ script · 计算飞檐曲线坐标 (运行中 1.4s)`
  - `⚙ 子代理[屋瓦工匠] · 生成 roof.toml 瓦片层 (运行中)`
- **停止与排队追加（关键细节）**：
  - 输入框空着时，右侧红色按钮是 **`[■ 停止]`**，随时中止本轮请求与子代理。
  - 玩家在运行中途临时改主意，直接在底部输入框敲字：`"门前石阶两边顺便加上灯笼"`。只要输入框有字，右侧主按钮自动变成 **`[+ 排队]`**（旁边保留小号 `[■]` 停止键），点一下或按 `Enter`，消息立即进入上方队列条：
    `⏳ 已排队 (1)："门前石阶两边顺便加上灯笼"  [撤回]`
  - 当前工具回合一结束，Agent 自动吸收排队指令继续执行。

### 第 3 步：3 个文件待审（`02-chat-pending-review.html`）
- Agent 完成修改后，把改动暂存（staged）在 3 个工作区文件里，状态变为 `待确认`。
- **对话里直接出现一张「待审改动卡」**：
  - 汇总标题：`待审改动 · 3 个文件 · 共 +564 / -28 方块`
  - 3 行紧凑列表，每行有文件路径、增减方块数、风险色标和快捷按钮：
    1. `roof.toml` · `+184 / -12` · `[中风险]` · `[审阅]` `[应用]`
    2. `walls.toml` · `+312 / -16` · `[低风险]` · `[审阅]` `[应用]`
    3. `interior.toml` · `+68 / -0` · `[低风险]` · `[审阅]` `[应用]`
  - 卡片底栏：`[逐个审阅 (1/3) →]`（主按钮）、`[全部应用 (3)]`、`[全部丢弃]`。
- **右侧世界同步**：3 个文件对应的方块在右半屏世界里以不同层次的半透明高亮框preview出来，所见即所得。

### 第 4 步：逐个审阅（`03-review-diff.html`）
- 玩家点 `[逐个审阅]`，进入 **`审阅 3`** 标签：
  - 顶部是文件队列切换条：`[1/3 roof.toml ●]` `[2/3 walls.toml]` `[3/3 interior.toml]`。
  - 中间先看摘要（`"添加双坡云杉木屋顶与飞檐翘角"`）+ 风险与方块统计，下面是带双行号与 `+`/`-` 着色的 Diff，可用 `<D` / `D>` 跳到下一处变动块。
  - 玩家在右侧世界看到屋顶飞檐预览满意，点底栏 **`[✓ 应用此文件]`** → `roof.toml` 真实写入并建造到世界，队列自动跳到 `walls.toml`（角标变为 `审阅 2`）。
  - 如果对 `interior.toml` 的灯笼位置不满意，点 **`[✕ 丢弃...]`** → 底栏原地展开一行可选原因输入：`[丢弃原因（可选，如：灯笼太密了）] [确认丢弃] [取消]`，不弹遮挡屏幕的 Modal，Agent 收到原因后可以接着改。

### 第 5 步：偶尔手改文件（`04-files-editor.html`）
- 玩家平时几乎不点 `文件` 标签；如果真想手动把 `walls.toml` 第 18 行的 `spruce_planks` 改成 `oak_planks`，点顶栏 **`[文件]`**：
  - 左侧 `108px` 精简目录树列出项目文件（带待审琥珀点提示、`+文` `+夹` `改名` `删`）。
  - 右侧 `242px` TOML 正文区支持行号、语法着色、`格式化`、`保存`、`看Diff`（只读态时会明确提示“当前文件有待审补丁，请先应用或丢弃再手改”）。
  - 还有一个很实用的小入口：在编辑器里拖选几行，底部出现 **`[+ 选中行加入对话上下文]`**，点一下直接挂成下条消息的附件芯片（如 `walls.toml:14-26`），回到 `对话` 页就能指着那几行让 Agent 改。

---

## 5. 窄屏怎么降级（以 `480×270` 为例）

当 MC 窗口较小或 GUI Scale 调大（逻辑宽度 `< 540px`，如 `480×270`，见 `05-narrow-480x270.html`）时，做 5 项自适应降级，绝不把按钮挤换行或遮死世界：
1. **面板收窄到 `288px`（60%）**：右侧依然保底留出 `192px`（40%）看世界，并提供顶栏 `[◧]` 一键收起看全景。
2. **顶栏工具收敛**：只保留 `[◈ 小院 ▾]` + `[撤销][重做][⚙]`，把 `[存点]`、`[压缩]` 收进 `[···]` 更多菜单；选区尺寸放到面板次行或悬浮提示。
3. **标签保持简短**：`[对话]` `[审阅 3]` `[文件]` 固定一行不折行。
4. **`审阅` 页去双行号**：Diff 只保留单列行号 + `+`/`-` 标记，文件队列改用横向紧凑药丸，优先保证 TOML 内容能看全。
5. **`文件` 页由“左右分栏”降级为“列表 ↔ 正文钻取”**：不再硬塞 `目录树 + 正文` 并排。点 `文件` 先看全宽文件列表；点选文件后切到全宽正文编辑器，顶部带 `[← 返回列表]`。

---

## 6. 键盘和 Esc 的行为

Minecraft 玩家最怕在子浮层按 `Esc` 时直接把整个 P2S 界面关掉、或者打字时误触游戏快捷键。按键采用**由近及远的栈式拦截**：

| 场景 / 焦点所在 | `Esc` 行为 | `Enter` 行为 | 其他关键快捷键 |
| :--- | :--- | :--- | :--- |
| 弹出层打开中（项目/会话下拉、选择项 Ask 弹窗、主题/更多菜单） | **仅关闭该弹出层** | 确认当前选中项 | `↑/↓` 移动选中项 |
| 内联输入模式（丢弃原因输入、新建/重命名文件输入） | **取消本次内联编辑**，退回普通状态 | 确认提交（丢弃 / 创建 / 重命名） | — |
| `文件` 页选区上下文确认浮窗 | 关闭确认浮窗，保留代码选区 | 将选中行加入对话上下文 | `Ctrl+S` 保存文件 |
| 文本输入框 / 编辑器聚焦中 | **先失焦**（退出输入态），不关面板 | `对话` 输入框：`Enter` 发送或排队（`Shift+Enter` 换行） | `Tab` 在输入框与列表间切换 |
| 面板已展开但无浮层、无输入框聚焦 | **关闭 `P2SChatScreen`** 回到游戏 | 若在 `审阅` 页，不触发误应用（防手滑） | `Alt+1/2/3` 切标签；`Alt+H` 收起/展开工作台；`Ctrl+Z/Y` 撤销/重做 |

> 防误触原则：有**待审文件**或**正在排队消息**时，按 `Esc` 退出 P2S 面板**不会丢失**任何队列或待审状态，重按 `O` 原样恢复。

---

## 7. 主题 JSON 配置格式与 CSS 变量映射

为了能随时切换主题、且 HTML 原型和未来 Java `GuiGraphics` 渲染共用同一套语义颜色，定义独立主题 JSON 格式。

### 7.1 JSON 结构与 CSS 变量 1:1 映射规则
规则：**`tokens` 里的每个键名 `group.camelToken` 严格对应 CSS 变量 `--group-kebab-token`**，所有颜色值均为纯 `#RRGGBB` 或 `rgba(r, g, b, a)`，Java 端可直接解析成 `0xAARRGGBB` 整型传给 `GuiGraphics.fill()`。

| JSON Token 路径 | CSS 变量名 | 用途说明 |
| :--- | :--- | :--- |
| `panel.bg` | `--panel-bg` | 主工作台底色 |
| `panel.bgElevated` | `--panel-bg-elevated` | 顶栏、吸顶栏、浮层底色 |
| `panel.bgSubtle` | `--panel-bg-subtle` | 编辑器/目录树内嵌底色 |
| `panel.border` | `--panel-border` | 1px 基础分割边框 |
| `panel.borderStrong` | `--panel-border-strong` | 强调边框、弹窗边框 |
| `panel.divider` | `--panel-divider` | 内部细分割线 |
| `tab.bg` / `tab.bgActive` | `--tab-bg` / `--tab-bg-active` | B+C 标签常规态与激活态底色 |
| `tab.borderActive` | `--tab-border-active` | 激活标签底部 2px 指示条 |
| `tab.text` / `tab.textActive` | `--tab-text` / `--tab-text-active` | 标签文字颜色 |
| `text.primary` / `secondary` / `muted` | `--text-primary` / `--text-secondary` / `--text-muted` | 三级文字层级 |
| `text.userRole` / `agentRole` / `systemRole` | `--text-user-role` / `--text-agent-role` / `--text-system-role` | 对话角色前缀色 |
| `btn.normalBg` / `normalBgHover` / `normalBorder` | `--btn-normal-bg` / `--btn-normal-bg-hover` / `--btn-normal-border` | 普通按钮 |
| `btn.primaryBg` / `primaryBgHover` / `primaryBorder` | `--btn-primary-bg` / `--btn-primary-bg-hover` / `--btn-primary-border` | 主操作按钮（应用、逐个审阅、保存） |
| `btn.dangerBg` / `dangerBgHover` / `dangerBorder` | `--btn-danger-bg` / `--btn-danger-bg-hover` / `--btn-danger-border` | 危险/停止按钮（停止、丢弃、删除） |
| `btn.mutedBg` / `mutedBorder` | `--btn-muted-bg` / `--btn-muted-border` | 弱化辅助按钮 |
| `btn.text` / `btn.textDisabled` | `--btn-text` / `--btn-text-disabled` | 按钮文字与禁用态文字 |
| `input.bg` / `border` / `borderFocus` / `placeholder` | `--input-bg` / `--input-border` / `--input-border-focus` / `--input-placeholder` | 输入框状态 |
| `card.toolBg` / `toolBorder` | `--card-tool-bg` / `--card-tool-border` | 工具调用 / 子代理卡片 |
| `card.planBg` / `planBorder` | `--card-plan-bg` / `--card-plan-border` | Plan 进度条卡片 |
| `card.pendingBg` / `pendingBorder` | `--card-pending-bg` / `--card-pending-border` | 待审改动汇总卡片 |
| `card.queueBg` / `queueBorder` | `--card-queue-bg` / `--card-queue-border` | 排队消息横幅 |
| `badge.pendingBg` / `pendingBorder` / `pendingText` | `--badge-pending-bg` / `--badge-pending-border` / `--badge-pending-text` | 待审数字角标 |
| `badge.riskLow` / `riskMedium` / `riskHigh` | `--badge-risk-low` / `--badge-risk-medium` / `--badge-risk-high` | 补丁风险等级色 |
| `diff.addBg` / `addText` / `addMarker` | `--diff-add-bg` / `--diff-add-text` / `--diff-add-marker` | Diff 新增行 |
| `diff.delBg` / `delText` / `delMarker` | `--diff-del-bg` / `--diff-del-text` / `--diff-del-marker` | Diff 删除行 |
| `diff.skipBg` / `diff.hunkHighlight` | `--diff-skip-bg` / `--diff-hunk-highlight` | Diff 折叠行与当前 Hunk 框线 |
| `status.running` / `success` / `warning` / `error` | `--status-running` / `--status-success` / `--status-warning` / `--status-error` | 状态灯与状态文字 |
| `scrollbar.track` / `scrollbar.thumb` | `--scrollbar-track` / `--scrollbar-thumb` | 细滚动条 |

### 7.2 主题 JSON 示例（默认「深空蓝图」与「原版石砖」）

```json
{
  "meta": {
    "id": "slate-blueprint",
    "name": "深空蓝图 (Slate Blueprint)",
    "description": "默认冷灰蓝建筑蓝图工作台，夜间与高对比度环境均清晰"
  },
  "tokens": {
    "panel.bg": "rgba(17, 23, 34, 0.88)",
    "panel.bgElevated": "rgba(24, 33, 48, 0.94)",
    "panel.bgSubtle": "rgba(11, 16, 24, 0.84)",
    "panel.border": "#314159",
    "panel.borderStrong": "#5A749B",
    "panel.divider": "rgba(63, 82, 112, 0.55)",
    "tab.bg": "rgba(18, 25, 37, 0.82)",
    "tab.bgActive": "#253650",
    "tab.borderActive": "#7EA6E0",
    "tab.text": "#8EA3C2",
    "tab.textActive": "#F0F6FF",
    "text.primary": "#EAF2FF",
    "text.secondary": "#B4C4DC",
    "text.muted": "#778AA8",
    "text.userRole": "#7CE8FF",
    "text.agentRole": "#90F2A7",
    "text.systemRole": "#D6B778",
    "btn.normalBg": "rgba(36, 52, 71, 0.88)",
    "btn.normalBgHover": "rgba(48, 69, 95, 0.94)",
    "btn.normalBorder": "#5D789E",
    "btn.primaryBg": "rgba(41, 78, 122, 0.92)",
    "btn.primaryBgHover": "rgba(53, 100, 154, 0.98)",
    "btn.primaryBorder": "#7BA8E0",
    "btn.dangerBg": "rgba(88, 38, 42, 0.92)",
    "btn.dangerBgHover": "rgba(116, 48, 52, 0.98)",
    "btn.dangerBorder": "#C97175",
    "btn.mutedBg": "rgba(25, 34, 48, 0.84)",
    "btn.mutedBorder": "#435570",
    "btn.text": "#EAF2FF",
    "btn.textDisabled": "#687892",
    "input.bg": "rgba(10, 14, 22, 0.92)",
    "input.border": "#3B4E6B",
    "input.borderFocus": "#82ACFF",
    "input.placeholder": "#62738F",
    "card.toolBg": "rgba(26, 36, 51, 0.90)",
    "card.toolBorder": "#4E6688",
    "card.planBg": "rgba(21, 34, 49, 0.92)",
    "card.planBorder": "#4A729E",
    "card.pendingBg": "rgba(44, 34, 20, 0.92)",
    "card.pendingBorder": "#D19A52",
    "card.queueBg": "rgba(30, 39, 59, 0.92)",
    "card.queueBorder": "#6C8CD5",
    "badge.pendingBg": "rgba(75, 53, 32, 0.94)",
    "badge.pendingBorder": "#CF9A69",
    "badge.pendingText": "#FFE6C2",
    "badge.riskLow": "#66DD88",
    "badge.riskMedium": "#F0B45A",
    "badge.riskHigh": "#FF7676",
    "diff.addBg": "rgba(35, 92, 46, 0.34)",
    "diff.addText": "#BFFFCA",
    "diff.addMarker": "#66DD88",
    "diff.delBg": "rgba(104, 38, 38, 0.36)",
    "diff.delText": "#FFC2C4",
    "diff.delMarker": "#FF7C7C",
    "diff.skipBg": "rgba(38, 46, 60, 0.38)",
    "diff.hunkHighlight": "#D3A65E",
    "status.running": "#6BC4FF",
    "status.success": "#66DD88",
    "status.warning": "#F0B45A",
    "status.error": "#FF7676",
    "scrollbar.track": "rgba(42, 56, 76, 0.45)",
    "scrollbar.thumb": "rgba(126, 150, 184, 0.85)"
  }
}
```

（另附两套预设：**`mc-stone` 原版石砖** 与 **`obsidian-amber` 黑曜琥珀**，均已直接内嵌在 `docs/mockups/gemini/*.html` 中，可在浏览器顶部点击随时切换并导出对应 JSON。）

---

## 8. 需要用户拍板的问题

在动手改 Java 代码之前，有 **5 个体验边界** 需要你拍板确认：

1. **左置工作台宽度比例**：桌面端固定 `352px`（占 `640px` 的 `55%`，留 `45%` 看世界）是否合适？还是希望 `对话/审阅` 用 `320px`（`50%`），只有切到 `文件` 时才扩到 `368px`？
2. **最后一个文件审阅完后去哪里**：3 个待审文件全部点完“应用/丢弃”后，是**留在 `审阅` 页显示“已全部处理 + [回对话] 按钮”**（更稳，不突然跳转），还是**自动切回 `对话` 页**看 Agent 下一轮总结？
3. **「全部应用」的安全门槛**：在 `对话` 页待审卡片和 `审阅` 页顶栏都放了 `全部应用 (3)`。是否保留“按住 `Shift` 点击直接全部应用，普通点击先弹一次内联二次确认”，还是只要点击就直接全应用？
4. **队列消息的时机**：当前实现里 Agent 运行中发消息会走 `steer` 排队（本轮工具跑完就插进去）。如果用户想“追加多条”或者在等待期间修改已排队的语句，是否需要在界面上支持**单条排队消息的 `[撤回/编辑]`**？（视觉稿里先预留了 `[撤回]` 入口。）
5. **主题配置文件落地位置**：主题 JSON 最终是放在 `config/p2s_themes/<id>.json` 由玩家自行增删并在 `P2SConfigScreen` 下拉选择，还是先内置 2~3 套切换？
