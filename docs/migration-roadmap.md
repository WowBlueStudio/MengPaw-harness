# Harness 核心迁移路线图

> 目标: 把 MengPaw 的 ReAct 核心抽为跨平台 Harness, **不重写、不 fork** — 原地接口化后逐步搬运。
> 起点: MengPaw v0.47.0 / kernel 内联实现。基线: 663 内核用例全绿。

## 阶段划分

| 阶段 | 内容 | 状态 |
|---|---|---|
| A | 平台抽象层落位 — 接口定义 + kernel 适配器 + ReAct 主链路注入点 | ✅ 完成 (2026-08-21) |
| B | 本仓库接收核心逻辑 — 按包搬运, 每搬一个包跑通编译与测试 | ⏳ 进行中 |
| C | 宿主接入方式切换 — MengPaw 经 `includeBuild` 消费本仓库, kernel 内联实现下线 | ⬜ 未开始 |
| D | 第二宿主验证 — 至少一个非 Android 宿主 (CLI/桌面) 实跑 | ⬜ 未开始 |

## A 阶段已完成

MengPaw 主仓库 (`mengpaw-kernel`) 内:

- 新增 `com.mengpaw.kernel.harness` 包 — 6 个抽象文件, 零平台类型
- 新增 `HarnessKernelAdapters.kt` — `KernelLogBridge` / `KernelConfirmGate` / `CliPipelineToolInvoker`
- `DataPaths` 转为抽象层门面 — 旧常量 API 保留 (152 个调用点零改动), 新增 `resolver` / `fs` 入口
- `AgentEngine` 新增 `harnessEnv` / `toolInvoker` 构造参数 (均有默认值, 行为不变)
- `AgentToolRunner` 工具执行改经 `HarnessToolInvoker`

**铁律**: 未显式注入时, 行为与改造前逐字等价 — 663 内核用例验证。

## B 阶段待搬模块

规模与障碍已实测 (文件数 / 主要障碍 / 优先级):

| 目标包 | 文件 | 主要障碍 | 优先级 |
|---|---|---|---|
| `llm` ReAct 纯逻辑 (`ReActParser` / `ReActTypes` / `LoopDetector` / `SseStreamParser` / `ReasoningExtractor`) | ~6 | 无 — 纯逻辑, 可立即搬 | P0 |
| `cli` 解析器 + `CommandRegistry` | ~11 | 依赖 `plugin` 包 (插件命名空间), 需抽出 `ToolRegistry` 接口 | P0 |
| `session` (`Session` / `SessionManager` / 压缩) | ~10 | `Message`/`Session` 数据模型需可序列化; 压缩阶段调 LLM (已是接口) | P0 |
| ReAct 骨架 (`AgentReActLoop` / `AgentReActStepProcessor` / `AgentToolRunner`) | ~3 | 依赖 `agent.AgentDocs` (记忆) / `evolution` / `security` — 均需接口化 | P1 |
| `agent` 文档系统 (`AgentDocs` 40+ API) | ~39 | **最大障碍**: 全静态 object + 直接文件 IO; 需转 `WorkspaceMemory` 接口 + FS 注入 | P1 |
| `security` 分级 (`RiskGate` / `CommandRiskLevels` / `SourceBlocklist`) | ~15 | `PromptFirewall`/`IntegrityGuard` 属宿主安全策略, 保留在 MengPaw | P2 |
| `evolution` 进化归因 | ~8 | 属 MengPaw 领域逻辑, 核心只留 `EvolutionSink` 接口 | P2 |
| `LlmProvider` 实现 (`AdaptiveLlmProvider` / HTTP) | ~8 | Ktor okhttp 引擎需换 CIO 以跨平台 | P2 |

**明确不搬** (留在 MengPaw 应用侧):
`acp` (远程委托) / `mcp` / `namespace.sys` (Android 系统能力) / `trigger` (AlarmManager) /
`plugin` 热加载 (DexClassLoader) / `ports` / `spi` / `skill`。

## 已知技术债

1. **文件 IO 未收敛** — kernel 内 56 个文件直接调 `java.io.File` (324 处)。
   本仓库要求新代码一律走 `HarnessFileSystem`, 搬运时逐个替换。
2. **全局单例未消** — kernel 内 74 个 `object` 单例; ReAct 主链路直接引用约 12 个。
   本仓库禁止新增全局可变状态, 搬运时改注入。
3. **消息类型弱类型化** — `LlmProvider` 用 `List<Map<String, String>>` 传消息。
   搬运时应升级为 `@Serializable` data class (协议稳定性要求)。
4. **DexClassLoader 热加载** — MengPaw 插件机制不可跨平台, 核心只保留 `HarnessToolInvoker` 扩展点。

## 迁移操作约定

1. **一次一个包**, 搬完立刻跑 `./gradlew check` 与新仓库测试, 绿了才继续。
2. **同名同包**: 保留 `com.mengpaw.harness.*` 命名, 避免主仓库改造期大面积改 import。
3. **主仓库不动**: 搬运期间 MengPaw 继续用内联实现 (已是最新行为), 不产生双份维护 —
   因为搬运是**移动**而非复制, 移走一个文件即在主仓库删除它。
4. **每阶段提交**: 主仓库与本仓库各自 commit, message 用 `feat:`/`refactor:` 前缀。
5. **不发布版本**: 本仓库 tag / JitPack 发布需用户明确指令。
