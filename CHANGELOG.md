# Changelog

本文件记录 MengPaw Harness 的版本变更。版本号独立于 MengPaw 主仓库演进
（主仓库版本在 `gradle.properties` 的 `mengpaw.version`，本库在 `harness.version`）。

JitPack 坐标：`com.github.WowBlueStudio:MengPaw-harness:<tag>`
（规则：`com.github.<用户>:<仓库名>:<tag>`）

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
