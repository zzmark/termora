# Windows ZIP 冷启动基线与优化记录

本文只覆盖 Windows ZIP 发行版的启动测量。当前阶段不评估 MSIX、CDS 或 AOT Cache 的性能，也不会把 `.jsa`、`.aot`、`.aotconfig` 等缓存产物加入 ZIP。

## 当前状态

截至 2026-08-16，Phase 0 基线探针、采集脚本和静态启动画面已经落地，代码提交为 `eb9fe86 feat(startup): add baseline tracing and static splash`。

本阶段的边界如下：

- 只改变 Windows ZIP app-image 的测量能力和首次视觉反馈；
- 探针默认关闭，普通启动不会创建轨迹文件或启动探针写入线程；
- baseline 子进程显式跳过 AOT 准备，以避免 record/restart 污染样本；该开关不改变普通用户启动；
- ZIP 中不打包 AOT Cache、CDS archive 或专用 profiling runtime；
- 静态 splash 不代表主窗口已完成绘制或已经可交互。

### 已落地的文件职责

| 文件 | 职责 |
| --- | --- |
| `StartupProbe.kt` | 记录单次启动阶段、生成 JSON，并在完成后异步原子写盘；退出时可保留部分轨迹 |
| `StartupUiProbe.kt` | 汇合首次绘制、Host 模型、Keymap 和 3 轮 EDT fence，产生 `interactive` |
| `Main.kt`、`ApplicationInitializr.kt` | 标记 JVM 入口、native library、日志、AOT 分支和单例检查 |
| `ApplicationRunner.kt` | 标记数据库、插件、LAF、扩展和主窗口投递/构造阶段 |
| `DatabaseManager.kt`、`PluginManager.kt` | 标记两个主要初始化器的内部构造边界 |
| `TermoraFrame.kt`、`NewHostTreeModel.kt` | 提供首次完整绘制、Keymap 和 Host 模型 ready 信号 |
| `Measure-TermoraStartup.ps1` | 校验 ZIP、隔离环境变量、启动进程、汇总 KPI，并拒绝不合格样本 |
| `StartupTraceSessionTest.kt` | 验证去重、并发记录、JSON、原子替换和 readiness gate 顺序 |
| `build.gradle.kts` | 把现有图标复制进 app-image，并为 Windows ZIP launcher 配置静态 splash |

### 已完成的验证

- `.\gradlew.bat :test --tests "app.termora.StartupTraceSessionTest" --no-daemon` 通过；
- `copy-dependencies` 通过，`termora-splash.png` 与源图标 SHA-256 一致；
- `Measure-TermoraStartup.ps1` 通过 PowerShell AST 解析；
- 脚本能识别由 IDE 或 `gradlew run` 启动、但进程名为 `java.exe` 的 Termora 单例 mutex；
- 一次非冷、无 splash 的协议烟测得到约 `4244 ms` TTFP、`4341 ms` TTI。该结果只证明采集链工作，不是冷启动 baseline，也不能用于评价 splash。

静态 splash 的完整交接烟测曾被正在运行的 IDE Termora 单例阻止。脚本现已将该情况改为启动前明确拒绝；后续需要在关闭所有 Termora 实例后重新执行 ZIP 实包验证。

## 启动链观察与候选方向

以下结论来自本轮代码路径检查，用于指导后续试探；除静态 splash 外，本阶段尚未改变这些业务初始化逻辑：

- 原有 `Application initialization Nms` 在 `invokeLater` 投递主窗口任务后结束，遗漏 launcher/JVM 前段、EDT 窗口构造、首次绘制和可交互阶段，因此不能作为首屏 KPI；
- `PluginManager` 虽由虚拟线程触发，但它与 `DatabaseManager` 都通过 `ApplicationScope` 的全局 monitor 完成实例构造，可能相互串行；主线程随后还有插件 `join` 硬屏障；
- `setupLaf()` 在首屏前访问 `NativeIcons.folderIcon`，Windows 下可能同步读取 shell 图标并创建临时文件；
- `TermoraFrame` 构造阶段同步解码多张窗口图标，并创建 toolbar、ActionManager、TerminalTabbed、Welcome 和 Host tree；
- `NewHostTreeModel` 初始化立即加载全部 Host，路径包含 SQLite 查询、解密、JSON 解码和排序；Keymap 初始化也会查询并解码全部配置。

后续真实 TTFP 优化应逐项试探，不把多种策略混在同一个样本中。优先候选包括移出首屏前的 shell icon I/O、缩小 `ApplicationScope` 构造锁范围、拆除不必要的插件 barrier，以及延迟 Host/Keymap 的非首屏数据加载。

## 测量目标

原有的 `Application initialization Nms` 在主窗口任务投递到 EDT 后就结束，不能代表首屏。新的启动探针默认关闭；只有显式设置测量环境变量时才在内存中记录事件，并在应用达到可交互状态后一次性原子写出 JSON。

基线使用以下指标：

| 指标 | 定义 |
| --- | --- |
| `processStartToFirstMainMillis` | Windows 报告的 launcher 进程创建时间，到本次用户启动中第一个 JVM 进入 `main` |
| `processStartToInteractiveMainMillis` | launcher 进程创建时间到最终显示主窗口的 JVM 进入 `main` |
| `processStartToFirstPaintMillis` | launcher 进程创建时间到 `TermoraFrame.paint` 首次完整返回；正式 TTFP KPI |
| `processStartToInteractiveMillis` | launcher 进程创建时间到 readiness 条件满足并通过 3 轮 EDT 队列屏障；正式 TTI KPI |
| `launcherToFirstMainMillis` | PowerShell 调用 `Start-Process` 前，到本次用户启动中第一个 JVM 进入 `main` |
| `launcherToInteractiveMainMillis` | PowerShell 时间锚点到最终显示主窗口的 JVM 进入 `main` |
| `launcherToFirstPaintMillis` | PowerShell 时间锚点到 `TermoraFrame.paint` 首次完整返回 |
| `launcherToInteractiveMillis` | PowerShell 时间锚点到首次绘制后连续通过 3 轮 EDT 队列屏障 |
| `mainToFirstPaintMillis` | 最终 JVM 的 `main` 到主窗口首次完整绘制 |
| `mainToInteractiveMillis` | 最终 JVM 的 `main` 到定义的可交互点 |

`interactive` 需要 Host 树与 Keymap 都已就绪、主窗口已完成首次绘制，且 EDT 又处理了 3 个连续队列回合。它是一个稳定、可重复的工程代理指标，并不等价于人工点击延迟。

正式比较以 `processStartToFirstPaintMillis` 和 `processStartToInteractiveMillis` 为主。`launcherTo*` 包含 PowerShell cmdlet 调度开销，只用于诊断外部调用上界；`mainTo*` 用于拆分 JVM 入口之后的应用耗时。

轨迹还包含数据库、插件、LAF、扩展、窗口构造、Host 树和 Keymap 等阶段。本轮 baseline 由脚本显式设置 `TERMORA_STARTUP_SKIP_AOT=true`，应用会在进入现有 AOT 管理逻辑前跳过该分支，不生成缓存、不改写 cfg。这个开关仅由测量子进程继承，脚本返回前会恢复调用方环境。

`processTraceCount` 大于 1 表示一次用户启动中出现了多个受监控 JVM，summary 会把 `baselineEligible` 标为 `false`，该样本应单独分析。jpackage 的原生 launcher PID 与实际 JVM PID 可能不同，不等同于多个 JVM；两者都会记录在 launcher metadata 中。脚本还要求完整轨迹包含 `aot-skipped`，防止旧包或环境变量传递失败时误收受 AOT 影响的样本。

## 一次协议验证

先用已解压的真实 ZIP 做一次非冷启动，确认探针、目录权限和数据集都正常。不要使用 `gradlew run` 作为发行版基线。

```powershell
pwsh -NoProfile -File .\tools\startup\Measure-TermoraStartup.ps1 `
  -TermoraExe 'D:\bench\Termora\Termora.exe' `
  -OutputDirectory 'D:\bench-results\termora'
```

如需隔离数据集，必须在测量前准备好目录，再传入：

```powershell
pwsh -NoProfile -File .\tools\startup\Measure-TermoraStartup.ps1 `
  -TermoraExe 'D:\bench\Termora\Termora.exe' `
  -BaseDataDir 'D:\bench-data\representative' `
  -OutputDirectory 'D:\bench-results\termora'
```

不传 `-BaseDataDir` 时，脚本会为测量子进程清除可能继承的 `TERMORA_BASE_DATA_DIR`，因此 Termora 使用自身的默认数据目录；调用方原有环境变量会在启动后恢复。

脚本会拒绝以下情况：

- 已存在 ZIP 进程，或 IDE/`gradlew run` 启动的 Java 进程持有 Termora 单例 mutex；
- 目标不是 `Termora.exe`；
- `Termora.exe` 旁缺少 ZIP app-image 的 `app\Termora.cfg` 或 `runtime`；
- 输出目录位于解压后的 Termora 目录内部；
- 指定的数据目录尚未准备好；
- 120 秒内没有收到 `interactive`，可通过 `-TimeoutSeconds` 调整。

应用会继续运行，采集完成后应正常关闭。脚本不会修改、终止或清理应用进程。

每次运行生成：

- `<run-id>-<pid>.json`：应用内阶段轨迹；若发生进程重启，会有多个 PID 文件；
- `<run-id>-launcher.json`：外部启动时间、PID、开机时间和运行上下文；
- `<run-id>-summary.json`：首屏指标，以及轨迹被脚本观察到时的 CPU、Peak Working Set；
- 超时时仍会保留 launcher 诊断文件和已经产生的部分轨迹。

## 真实完全冷启动流程

1. 在重启前完成构建、解压、数据准备和脚本语法验证。
2. 记录 ZIP 的 SHA-256、源码提交和工作区状态；同一组样本只能比较相同产物与相同数据集。
3. 关闭 Termora，并确认任务管理器中没有残留的 `Termora.exe`。
4. 使用 Windows 的“重启”，不要在重启后重新解压、复制数据、计算 ZIP 哈希或浏览应用目录。
5. 固定启动时机。建议选择“Explorer 可见后立即启动”或“登录后固定等待 30 秒”之一，整组样本保持一致。
6. 本次开机只运行一次采集脚本；出现结果后正常关闭 Termora。
7. 重复重启采样。试探阶段先取 5 次；流程稳定后取 10 次，报告 median、p90 和范围，不只看单次最好成绩。

下列场景要分组，不能混在同一统计集合：

- 日常真实数据与全新空数据；
- 无主密码与启用主密码；
- 内置插件与代表性的外部插件集合；
- 一次用户启动只有一个 JVM 与 `processTraceCount > 1` 的样本。

如果启用了主密码，人工输入时间会进入启动路径，应单独记录，并约定固定的解锁操作方式。

## 如何比较后续改动

先完成 1 次非冷协议验证，再做冷启动样本。每个候选改动使用相同 ZIP 构建方式、数据目录、系统登录等待时间和采样数量。

首屏性能改动至少检查：

- `processStartToFirstPaintMillis` 和 `processStartToInteractiveMillis` 的 median、p90；
- 每个阶段的耗时占比是否按假设变化；
- `processTraceCount` 是否改变；
- Peak Working Set 和 CPU 是否出现明显回退；
- 主密码、数据库失败、第二实例等路径是否仍正确。

启动画面只改善“首次视觉反馈”，不能替代 TTFP/TTI。后续 splash A/B 必须继续使用同一组主窗口指标，并另行记录首次视觉反馈时间。

## 静态启动画面试验

Windows ZIP 构建会把现有 `termora_256x256.png` 复制为 `app/termora-splash.png`，并向 jpackage launcher 添加：

```text
java-options=-splash:$APPDIR/termora-splash.png
```

该画面由 native launcher 在 `main()` 之前显示，不创建额外的 Swing 窗口，也没有动画或模拟进度。第一个 AWT/Swing 窗口出现时由 JVM 自动关闭，因此主密码解锁框和主窗口都能自然接管。

本试验只应评价 `T_feedback` 是否提前；`processStartToFirstPaintMillis` 和 `processStartToInteractiveMillis` 仍需保持不回退。还要单独检查第二实例、异常对话框和高 DPI 下是否出现短暂闪屏、尺寸过大或交接跳变。

当前应用内探针从 `main()` 才开始记录，无法直接得到 splash 的出现时间。`T_feedback` 需要用固定帧率录屏或单独的 launcher/ETW 诊断测量；不要从 `mainTo*` 指标反推。

### ZIP 实包验收

1. 关闭 ZIP、IDE 和 `gradlew run` 启动的所有 Termora 实例。
2. 按正常 Windows ZIP 流程执行 `jar`、`copy-dependencies`、`jlink`、`jpackage` 和 `dist`。
3. 检查解压目录中存在 `Termora\app\termora-splash.png`，且 `Termora.cfg` 包含 `java-options=-splash:$APPDIR/termora-splash.png`。
4. 先做一次非冷协议验证，确认 splash 能自然交接到主密码窗口或主窗口，采集结果包含 `first-paint` 和 `interactive`。
5. 检查普通启动、第二实例、主密码、数据库异常以及 100%、150%、200% DPI。
6. splash A/B 各做至少 5 次真实重启样本。TTFP/TTI 不应明显回退；视觉收益另行记录为 `T_feedback`。

只有完成以上步骤后，才能把静态 splash 标记为“ZIP 实包验收完成”。当前状态是“代码与构建输入已验证，实包交接待验证”。

## 下一步试探顺序

1. 完成静态 splash 的 ZIP 实包交接和 DPI 验收，只判断视觉反馈与回退。
2. 采集 5 次无业务优化的真实冷启动 baseline，固定数据集、插件集合和登录后启动时机。
3. 优先试探首屏前的 `NativeIcons.folderIcon` shell I/O 后移，并与 baseline 单独 A/B。
4. 再验证 `ApplicationScope` 全局构造锁是否让数据库和插件串行；如确认，缩小锁内工作并重新测量。
5. 最后分别试探插件 barrier、Host 列表和 Keymap 加载后移。每次只改变一个主要变量。

每轮试探都应在本文追加：源码提交、ZIP 哈希、数据集描述、样本数、median/p90、异常样本和结论。没有完整冷启动数据时，只记录为候选或协议烟测，不写成性能收益。

## 深入诊断

应用内轨迹用于正式 KPI。需要定位 CPU、磁盘或文件 I/O 时，可以另做一次 WPR 诊断运行：

```powershell
wpr.exe -start GeneralProfile -filemode
# 运行一次 Measure-TermoraStartup.ps1
wpr.exe -stop D:\bench-results\termora-startup.etl
```

WPR 会扰动时序，因此 ETL 只用于解释瓶颈，不应混入正式 baseline 统计。当前 ZIP runtime 不要求包含 JFR 模块，Phase 0 也不会为此改变 jlink 模块集合。
