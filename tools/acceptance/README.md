# P2S 实机验收

本目录用于 Minecraft Java 1.21 和 1.21.1。一个运行目录对应一份待测程序、一份新复制的单人世界、一个私有 Xvfb 显示、一个回环模拟供应商和一份证据。默认输入键为 `O`，按键和截图都经过实际游戏窗口。

## 在这台验收机上运行

长期存储在 `/vol1/1000/fabric/.p2s-acceptance`。其中 `cache/jdk21` 是 JDK 21，`cache/bridge` 指向本机已构建的测试伴随模组；`worlds/1.21/P2SAccept` 是专门为验收创建的超平坦创造世界模板。1.21.1 可打开模板的独立副本。Java 不使用玩家原来的 Minecraft 目录或供应商密钥。

在本验收分支的工作树中执行以下命令。把 `--project` 和 `--mod-jar` 换成实际交付的路径。

```bash
python3 tools/acceptance/accept.py \
  --root /vol1/1000/fabric/.p2s-acceptance \
  --project /vol1/1000/fabric/p2s-wt-integration \
  --version 1.21.1 \
  --mod-jar /vol1/1000/fabric/p2s-wt-integration/build/libs/prompt2structure-mc1.21.1-1.0.0.jar
```

- 提交制品时保留 `--mod-jar`。启动器对候选 JAR 计算 SHA-256，原样复制到该轮的 `game/mods/subject.jar`，用正式 Fabric Loader 的 intermediary 环境启动。验收同时核对 FabricLoader 报告的来源和 JVM 入口类的加载路径。候选 JAR 不做重映射或补库。
- 提交源码时省略 `--mod-jar`。启动器用目标工作树的 Gradle/Loom 导出该版本的 named 开发类路径。若已编译，可以用 `--classpath-manifest` 指定同一工作树和版本的清单；修改源码后重新生成。
- 两个 MC 版本分别运行。换分辨率或语言可传 `--width 1920 --height 1080 --gui-scale 4 --lang zh_cn`，流式聊天用 `--stream`。基准和候选使用相同世界、语言、逻辑尺寸和用例。
- 终端第一行打印证据目录。退出码 0 表示选中的场景全部通过；非零时看 `acceptance.json`、`core/report.json`、`extended/report.json`、`client.log`、`launcher.log`。运行结束会关闭该次游戏和假供应商。可传 `--run-dir` 指定一个尚不存在的新目录。

## 验收内容与证据

| 场景 | 机器可核对的结果 |
|---|---|
| 运行身份 | MC 版本、开发或发行模式、窗口与逻辑尺寸、模组来源；发行包的 SHA-256 和入口类来源 |
| 正常交互 | 私有显示输入真实 `O` 并确认进入 `P2SChatScreen`，从游戏窗口截图；可继续真实点击、打字、检查焦点 |
| 聊天和多文件审批 | 创建选区和项目；请求到达假供应商；两个文件暂存时服务端方块保持原状；单项应用、丢弃、全部应用的方块与请求回执分别变化 |
| 导出和选择题 | 导出 ZIP 中存在相应版本的 TOML、对话、工具结果和配置；搜索本次随机假密钥；选择两个选项之一并等待后续对话 |
| 子代理与上下文 | 通过真实 `task` 创建只读规划子任务并核对其工具、等待及通知；手动压缩后核对存档和下一次实际模型请求 |
| 扩展用例 | 命名检查点、按文件 Undo/Redo、计划显示、工作区 read/受限 script/虚拟 sh、Stop 后的迟到响应、新旧会话的存储/回放及 UI 计划恢复 |
| 浏览器原型 | `check_mockups.py` 逐页运行 Playwright，对主题、情景控件、行为、资源和几何断言留图；报告中注明 viewport 和实际状态 |

基本用例通过后默认测试子代理与手动压缩，再运行其他扩展用例。用 `--suite core`、`--suite advanced` 或 `--suite extended` 可单独运行其中一组；核心失败后记录后续跳过原因。失败会保留现场证据。软件渲染器适用于交互和布局验证；真实 GPU 帧率、审美选择、外部供应商账户可用性需分别验证。新功能的验收点可加入独立场景：用 fixture 给出固定模型响应，从正常玩家入口执行，再从界面、服务器方块、真实网络请求或存档读取独立断言。

导出方便用户查阅的汇总和截图：

```bash
python3 tools/acceptance/export_review.py --run-dir /path/to/run --out /path/to/new-review-directory
```

导出目录包含 Markdown、测试结果及游戏截图，旁边另生成 ZIP。原始游戏目录、实例控制令牌、配置和完整日志留在私有运行目录。

## 交互式定位、换页、截图

需要停留在某个页面时，前台运行 `client.py`，提供 `--project`、`--version`、`--run-dir`、`--cache`、`--java-home`、`--world-template`、`--bridge` 和一份由 `fixture_provider.py` 在独立 seed 目录生成的测试 `--config`。开发模式加载命名为 `p2s-acceptance-bridge-1.21.jar` 的 companion；发行模式加载 `p2s-acceptance-bridge-1.21-intermediary.jar`，1.21.1 同理。测试客户端就绪后，在另一个终端执行：

```bash
python3 tools/acceptance/control.py --game-dir "$RUN/game" state
python3 tools/acceptance/ui.py --run-dir "$RUN" key o
python3 tools/acceptance/control.py --game-dir "$RUN/game" click-widget --run-dir "$RUN" Config
python3 tools/acceptance/ui.py --run-dir "$RUN" screenshot /path/to/frame.png
python3 tools/acceptance/inspect_screen.py --run-dir "$RUN" --game-dir "$RUN/game" --out /path/to/new-layout-directory
python3 tools/acceptance/ui.py --run-dir "$RUN" quit
```

控件名称取当前语言下的实际 label。`inspect_screen.py` 同时保存帧和该画面的可见控件几何；重叠候选需要结合画面检查。对于没有原版 widget 的自绘控件，使用 `ui.py click X Y`（游戏窗口内的物理像素）、`type`、`key` 和 `chord`。UI 快照不会读取输入框中的凭据。

历史 `feat/world-parse-debug` 提供 `/debug/agent` HTTP/SSE 接口，当前集成版未包含该实现。验收伴随模组作为单独 JAR 加载，只在明确标记的隔离测试实例绑定 127.0.0.1 随机端口。就绪文件自动记录本次端口；控制命令自行读取该文件。接口、动作和重新构建伴随模组步骤见 [桥文档](bridge/README.md)。

## 缓存、重建和源码级测试

- Linux 需要 Python 3.11+、JDK 21、Xvfb/xauth、X11/XTest 及 Mesa 软件 OpenGL。开发模式和制品准备使用本机已有的可信 Gradle/Mojang/Fabric 缓存。默认离线；初次补资源时可给 `client.py` 传 `--online`。视觉资源按 Mojang 索引和 SHA-1 校验，可选 `--assets full` 补齐音频等内容。
- 新建模板时，用空的私有运行目录启动 `client.py`，暂不指定世界模板，窗口采用 1280×720、GUI 2、英文。`template.py --run-dir "$RUN" create` 执行原版建档菜单；正常退出后用 `template.py --run-dir "$RUN" export --to /path/to/new-template` 导出。不要在玩家原存档上创建模板。
- 更换不兼容的协议、类名或版本时，按 [桥文档](bridge/README.md) 针对候选代码单独编译 companion，并用 `--bridge` 指定；发行测例始终加载交付的原 JAR。
- 逻辑回归使用目标工作树的 `./gradlew :1.21:test :1.21.1:test`。若目标已有已编译测试和 Gradle worker 类路径参数文件，也可用 JDK 21 直接执行 `java @CLASSPATH_ARGFILE tools/acceptance/probes/SuiteProbe.java`。`java --add-modules jdk.httpserver @CLASSPATH_ARGFILE tools/acceptance/probes/ProtocolProbe.java` 用只监听回环的模拟服务验证协议头、正文、结果和 HTTP 429。
- 原型检查需要已安装 Chromium 的 Playwright Python 环境。`check_mockups.py /path/to/docs/mockups --out /path/to/new-report` 输出每个页面、尺寸和主题的结果与截图；测试目标应固定在同一个 Git 快照。游戏验收的依据仍是运行中 Fabric 的画面和业务状态。
