# 单人验收观测接口

此客户端 Fabric companion 只用于自动验收。P2S 的发布 JAR 无需打入这个 mod。使用独立 gameDir、独立单人世界和本机模拟模型。

## 构建与启动

需要与待测版本相同的 Java 21、Loom named 游戏 API 和 P2S 客户端编译类路径。类路径文件为单行、平台分隔的 Java classpath，也接受 Gradle test worker 的两行 `-cp` 文件。开发启动示例：

```sh
python3 build.py --version 1.21 --classpath-file "$P2S_NAMED_CP"
```

在默认使用 intermediary 命名空间的 Fabric 发行客户端中，使用同一版本的 Loom Tiny v2 映射和 Tiny Remapper 的工具类路径生成发行命名 companion：

```sh
python3 build.py --version 1.21 --classpath-file "$P2S_NAMED_CP" \
  --mappings "$P2S_LOOM_MAPPINGS" --remapper-classpath-file "$P2S_TINY_REMAPPER_CP"
```

`build/p2s-acceptance-bridge-1.21.jar` 用于 named 开发环境，`build/p2s-acceptance-bridge-1.21-intermediary.jar` 用于同版本的标准 Fabric 发行环境。1.21.1 同理。命名转换只处理 companion 自身。把相应 companion 单独放进此次验收的 mods 目录，或使用 Fabric 的 `-Dfabric.addMods=...`。

创建新游戏目录，放入空标志文件 `.p2s-acceptance-world-fixture`。同时提供 JVM 参数 `-Dp2s.acceptance.enabled=true`、`-Dp2s.acceptance.gameDir=<绝对游戏目录>`，并以这个目录启动客户端。游戏进入客户端循环后，companion 在该目录原子写入权限 0600 的 `.p2s-acceptance-ready.json`。其中包含 `baseUrl`、`pid` 和此次进程随机生成的 `token`。测试脚本读取就绪文件，在每个请求附上 `Authorization: Bearer <token>`。客户端正常退出时会清理就绪文件。监听地址固定为 127.0.0.1，端口动态分配。

## 状态与动作

- `GET /state` 获取 MC/模组版本、Fabric 命名空间和 development 标志、模组来源文件名、界面类、逻辑和物理尺寸、窗口原点及顶层 widget 的文本和几何信息。输入框内容省略。标题界面支持此查询。加载指定目录内的单人世界后，附加玩家方块位置、选区、项目/会话 ID、工作区路径、待审、选择题、agent 忙闲、计划项和检查点。分类文字和控件标签取自真实游戏界面。非本机单人存档、开放 LAN 的世界以及标志文件失效时拒绝业务观测/操作。
- `GET /world/block?x=N&y=N&z=N` 在最多距离玩家 96 格的已加载区块读取同一坐标的服务端 `block` 和客户端 `clientBlock`。
- `POST /action` 接收 JSON，必填 `action`。下表中的业务方法和发包使用 P2S 的正常游戏入口。项目创建和打开返回经客户端实际收到的服务端回执，其余动作的 `accepted` 仅表示发起。等待 `/state` 与方块结果验证最终状态。如果操作使模型有机会继续运行，配置的模型接口须指向本机回环地址。

| action | 主要字段 |
|---|---|
| selection | `pos1`、`pos2` 都是世界坐标三元整数数组 |
| project.create / project.open | `name` / `id`；真实服务端 `receipt` 包含 `ok`、`id` 等 |
| workspace.select | `path`；等 `selectedPath` 和 `pendingPath` 命中目标后审批 |
| patch.apply / patch.discard | `path`；丢弃可带 `reason` |
| patch.apply_all | 应用当前全部待审 |
| message / choice | `text` / 待选项 `id` |
| stop / compact / export | 复用 P2S 停止、压缩和导出操作 |
| undo / redo / checkpoint.create | 创建检查点提供 `label` |
| session.new / session.restore | 恢复提供当前项目已存在的存档 `id` |

自动化可以根据 widgets 的逻辑坐标生成实际鼠标输入。将游戏窗口逻辑坐标换算成显示坐标时应考虑窗口原点、GLFW 帧缓冲与窗口比例以及 GUI 缩放。本机 Xvfb 下的单屏场景可直接按 `windowX + x * windowWidth / guiWidth` 换算，Y 同理。真实截屏、焦点和遮挡用显示系统测量。
