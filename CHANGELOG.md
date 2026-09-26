# Changelog

本文件记录 MengPaw Harness 的版本变更。版本号独立于 MengPaw 主仓库演进
（主仓库版本在 `gradle.properties` 的 `mengpaw.version`，本库在 `harness.version`）。

JitPack 坐标：`com.github.WowBlueStudio:MengPaw-harness:<tag>`
（规则：`com.github.<用户>:<仓库名>:<tag>`）

---

## v0.2.0 (2026-09-18) — 检查点续跑能力 + 抽象层单一事实源

### 新增
- **检查点与断点续跑**:
  - `CheckpointStore` 接口 (平台能力, 经 `HarnessEnv.checkpoints` 注入) +
    `Checkpoint` / `CheckpointStatus` / `CheckpointMessage` 模型。
  - `InMemoryCheckpointStore` (默认, 进程内) 与 `FileCheckpointStore`
    (JSON 落盘, 经 `HarnessFileSystem`, 参考接法 `FileCheckpointStore(env.fileSystem, env.paths.checkpointDir)`)。
  - `ReActEngine` 接线: 每步落 RUNNING 检查点, 终止时落 COMPLETED / FAILED;
    `run(task, resume = true)` 从 RUNNING 检查点恢复历史与步数 (不重复追加任务);
    新增 `checkpoint()` / `clearCheckpoint()` 供宿主巡检与清理。
  - **检查点失败不中断主任务** (磁盘满/权限不足时任务照跑), 异常经 `onCheckpointError` 上报, 默认静默。
- **会话 id 消毒** (`sanitizeCheckpointId`): 点号一并替换, `../../` 无法存活 —
  落盘实现拼路径前必调, 防目录穿越。

### 变更
- `HarnessEnv` 新增 `checkpoints` 字段 (带默认值, 源码兼容); 默认内存实现,
  `jvmDefault` 行为不变 (要跨进程恢复需显式传 `FileCheckpointStore`)。
- `verifyNoPlatformTypes` 门禁**覆盖核心全部子包** (`engine` / `tool`), 仅排除 `.jvm`;
  此前只查顶层文件, 存在退化盲区。
- 测试 44 → **56 用例** (新增 `CheckpointStoreTest` 6 + `ReActCheckpointTest` 6), 全绿。

### 说明
- **抽象层单一事实源**: MengPaw kernel 内联的 `com.mengpaw.kernel.harness` 副本已删除,
  宿主改经 `com.mengpaw.harness.*` — 两边曾是同源码的两个副本, 已实测出现语义漂移
  (`HarnessToolRequest.ofRaw` 空值处理、`socket` 目录名), 收敛后不可能再漂。
  kernel 侧的 `KernelHarnessEnv.default()` 承接原 `HarnessEnv.fromKernelGlobals()` 语义。

---

## v0.1.1 (2026-08-21) — 发布坐标修正

### 修复
- **发布坐标改为 JitPack 标准形式**：`group` 由 `com.github.WowBlueStudio.MengPaw-harness`
  修正为 `com.github.WowBlueStudio`。此前把仓库名并入了 group，与 JitPack 的坐标映射规则
  （`com.github.<用户>:<仓库名>:<tag>`）不符。
  实测依据：以 `com.github.WowBlueStudio:MengPaw-harness:v0.1.0` 可正常解析并编译运行，
  而按 pom 中的 group 拼出的坐标无法解析。
- 主仓库侧同步：`settings.gradle.kts` 的 `dependencySubstitution` 与
  `mengpaw-kernel/build.gradle.kts` 的依赖声明改为同一坐标 —
  本地复合构建与远端 JitPack 依赖从此使用**同一个坐标**，消除"本地一套、远端一套"的分歧。

### 说明
- v0.1.0 仍在 JitPack 可用（坐标为 `com.github.WowBlueStudio:MengPaw-harness:v0.1.0`）。
  v0.1.1 仅修正发布坐标与主仓库为同一形式，功能代码无变更 — 两者行为一致。

---

## v0.1.0 (2026-08-21) — 首个公开版本

ReAct 循环 + 平台抽象层 + 工具协议，从 MengPaw 主仓库抽离。

### 新增
- **平台抽象层**：`HarnessEnv` / `HarnessFileSystem` / `HarnessPathResolver` /
  `HarnessClock` / `HarnessLogger` / `HarnessConfirmGate` / `HarnessToolInvoker`。
  接口零平台类型，由构建门禁 `verifyNoPlatformTypes` 强制。
- **ReAct 循环**：`ReActEngine`（自包含）+ `PromptBuilder`（协议提示词）+
  `Conversation`（最小会话契约，默认内存实现）。
- **工具系统**：`HarnessTool` 协议 / `ToolRegistry` 注册表 /
  `BuiltinTools` 六个开箱工具（`file.read` `file.write` `file.ls` `file.glob`
  `env.get` `net.get`）/ `ShellRunTool`（**内置但默认关闭**，三种显式开启方式）。
- **模型层**：`LlmProvider` 接口 + `AdaptiveLlmProvider`（任意 OpenAI 兼容端点）+
  SSE 流解析 + 推理链分流 + 重试限速。
- **解析层**：`ReActParser`（ReAct 文本 / XML / JSON 三形态容错）、`LoopDetector`。

### 可靠性设计（相对 toy 实现的差别）
空响应重试 / 循环检测 / 自适应步数扩展 / 退化输出拦截 / 工具超时 /
有界并行 / Observation 声明为数据非指令 / 确认门 fail-closed。

### 平台支持
Windows / macOS / Linux 经 JVM 路线直接可用（同一份产物三端运行）。
iOS 与 OpenHarmony 暂缓，后续工作与能力边界见 README「平台支持」一节。

### 许可
双许可：AGPL-3.0-or-later OR LicenseRef-Commercial。
