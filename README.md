# MengPaw Harness

[![License](https://img.shields.io/badge/license-AGPL--3.0--or--later%20OR%20LicenseRef--Commercial-blue)](LICENSE)

跨平台 Agent Harness 核心 — 一个自包含的 **ReAct 循环 + 平台抽象层 + 工具协议**。
从 [MengPaw](https://github.com/WowBlueStudio/MengPaw) 抽离，可独立作为库使用。

**不绑定**特定模型厂商、特定工具形态、特定平台。

---

## 5 分钟上手

```kotlin
val env   = HarnessEnv.jvmDefault(baseDir = "/path/to/data")  // ① 平台环境
val tools = BuiltinTools.registry(env)                        // ② 内置 6 个工具
val llm   = AdaptiveLlmProvider(                              // ③ 任意 OpenAI 兼容端点
    apiKey = System.getenv("DEEPSEEK_API_KEY"),
    apiEndpoint = "https://api.deepseek.com/v1/chat/completions",
    model = "deepseek-chat"
)
val engine = ReActEngine(llm, tools, maxSteps = 20)           // ④ 循环 (提示词自动生成)
val result = engine.run("统计 ./src 下有多少个 Kotlin 文件")
println(result.answer)
```

跑起来只需三件东西：**一个模型、一组工具、一个数据目录**。
可运行示例见 [`src/test/kotlin/.../examples/QuickStartExample.kt`](src/test/kotlin/com/mengpaw/harness/examples/QuickStartExample.kt)（同时是 CI 测试，保证文档不腐烂）。

## 部署

```kotlin
// JitPack (待推送到 GitHub 并打 tag 后可用)
implementation("com.github.WowBlueStudio.MengPaw-Harness:mengpaw-harness:<tag>")
```

要求 JDK 17。

## 核心概念

```
宿主 (Android / CLI / 桌面 / Web)
  │ ① HarnessEnv          平台能力: 文件系统 / 路径 / 时钟 / 日志 / 确认门
  │ ② HarnessToolInvoker  工具: 工具集从哪来 (内置 / 自定义 / CLI / MCP)
  ▼
ReActEngine  ── 思考 → 行动 → 观察 循环
  │
  └─→ LLM (任何 OpenAI 兼容端点)
```

**两条轴刻意分开**：平台能力走 `HarnessEnv`，工具形态走 `HarnessToolInvoker`。
混在一起会导致"换个工具形态就得改平台实现"。

## 模块

| 包 | 内容 |
|---|---|
| `com.mengpaw.harness` | 平台抽象层：`HarnessEnv` / `HarnessFileSystem` / `HarnessPathResolver` / `HarnessClock` / `HarnessLogger` / `HarnessConfirmGate` / `HarnessToolInvoker` |
| `com.mengpaw.harness.engine` | `ReActEngine` 循环 / `PromptBuilder` 提示词 / `Conversation` 会话契约 |
| `com.mengpaw.harness.tool` | `HarnessTool` 协议 / `ToolRegistry` 注册表 / `BuiltinTools` 内置工具 |
| `com.mengpaw.kernel.llm` | 模型层：`LlmProvider` 接口 / `AdaptiveLlmProvider` HTTP 实现 / SSE 流解析 / ReAct 解析器 / 循环检测 |

> 注：`com.mengpaw.kernel.*` 这些包由本库**提供**（自 MengPaw 搬入）。
> 包名暂沿用 `kernel` 以保持宿主侧 import 零改动，后续大版本会统一到 `com.mengpaw.harness.*`。

## 扩展

**加一个自己的工具**

```kotlin
val myTool = object : HarnessTool {
    override val name = "calc.eval"
    override val description = "计算两个整数的四则运算"
    override val parameters = "<a> <op> <b>"
    override val riskLevel = "low"                    // low / mid / high
    override suspend fun execute(input: ToolInput): ToolOutcome {
        val a = input.arg(0)?.toIntOrNull() ?: return ToolOutcome.fail("第一个操作数不是整数")
        // … 自己的逻辑
        return ToolOutcome.ok("$a …")
    }
}
val tools = ToolRegistry(env).register(myTool)
```

`high` 风险的工具会自动经过 `HarnessConfirmGate` 让用户二次确认（fail-closed：无人可问即拒绝）。

**接入已有的命令体系**（CLI / shell / MCP）

```kotlin
val tools = ToolRegistry.fromCommandRunner(
    env = env,
    specs = myCommandSpecs,
    runner = { name, rawInput -> runMyCommand(name, rawInput) }
)
```

**持久化会话**（默认是进程内内存实现）

```kotlin
class MyStore : Conversation {
    override fun messages(): List<Pair<String, String>> = loadFromDb()
    override fun add(role: String, content: String) = appendToDb(role, content)
    override fun clear() = clearDb()
}
```

## 设计取舍

这是**库**而不是框架，几处刻意的克制：

- **不替宿主决定存储**：`Conversation` 只要求"取历史 / 加消息"，落盘形态由宿主决定。
- **不内置高危工具**：执行任意命令、删除文件这类能力平台差异大且危险，应交由宿主显式提供并配自己的确认门。内置工具全部限制在 `baseDir` 内（路径穿越拒绝）。
- **不绑定模型**：`LlmProvider` 是接口，任何 OpenAI 兼容端点都能接；HTTP 引擎用 Ktor CIO（纯 Kotlin，不绑 JVM/Android）。
- **不做 UI**：事件回调（`StepEvent`）足够宿主渲染任意界面。

## 可靠性设计

生产环境跑 Agent 会遇到的问题，都在循环里处理了。这些是"能跑 demo"与"能跑生产"的差别：

| 机制 | 解决什么 |
|---|---|
| 空响应重试 | 部分供应商偶发返回空流；直接入库空消息会污染历史 → 不入库、重试一次、仍空则明确报错 |
| 循环检测 | 模型反复输出同一个 Action 会空转到步数耗尽 → `LoopDetector` 提前终止 |
| 自适应步数扩展 | 长任务被硬上限打断 → 临近上限且无失败累积时放宽 1.5 倍（有失败则不放宽，防放大成本） |
| 退化输出拦截 | 模型卡在重复标记（`<Action><Action>...`）→ 不当作最终答案 |
| 工具超时 | 坏工具永久挂起拖死循环 → 单工具 60s 超时 |
| 有界并行 | 模型一轮吐几十个 Action 击穿上游 → 单批默认最多 8 路并发 |
| 观察不可信 | 工具输出里含"请执行某操作"的注入文本 → 提示词层声明 Observation 是数据非指令 |
| 确认门 fail-closed | 无 UI 宿主（后台 / 无人值守）→ 高危操作一律拒绝，不静默放行 |

## 与 MengPaw 的关系

MengPaw（Android 微内核 Agent 框架）是本库的**主要宿主**，经 Gradle composite build
共享同一份源码（单一事实源，两边共同演进）。本库不反向依赖 MengPaw 的任何代码。

| 抽象 | MengPaw 侧实现 |
|---|---|
| `HarnessFileSystem` | `java.io.File` |
| `HarnessPathResolver` | 中文目录布局 + 消毒规则 |
| `HarnessConfirmGate` | 弹窗确认总线 |
| `HarnessToolInvoker` | CLI 命令管线 |

## 许可

双许可：社区版 **AGPL-3.0-or-later**，商业授权见 [`COMMERCIAL-LICENSE.md`](COMMERCIAL-LICENSE.md)。

提交即版权让渡。所有源码文件带 SPDX 头：

```
// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
```

## 构建

```bash
./gradlew check                    # 编译 + 测试 + 跨平台铁律门禁
./gradlew verifyNoPlatformTypes    # 只跑门禁: 核心源码禁止出现 java.*/android.* 类型
```

需要 JDK 17。文档：[接口契约](docs/interface-guide.md) · [迁移路线图](docs/migration-roadmap.md)
