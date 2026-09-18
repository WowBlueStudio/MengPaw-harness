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
implementation("com.github.WowBlueStudio.MengPaw-harness:mengpaw-harness:<tag>")
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

## 平台支持

**当前路线：PC 三端优先（JVM）。** 桌面三端各有官方 JDK 17，因此**同一份产物在三端均可运行**——
这是 JVM 路线的核心价值：不需要为每个平台重新编译，也无需交叉编译工具链。

| 平台 | 路径 | 状态 |
|---|---|---|
| **Windows** | JVM | ✅ **已支持** |
| **macOS** | JVM (Intel / Apple Silicon 均有官方 JDK) | ✅ **已支持** |
| **Linux** | JVM (glibc / musl 均可) | ✅ **已支持** |
| iOS | Kotlin/Native (darwin 源集) | ⏸️ 暂缓（能力受限，见下） |
| OpenHarmony 标准系统 | Kotlin/Native (linuxArm64) → `.so` → NAPI | ⏸️ 暂缓（需自建工具链） |
| 鸿蒙 LiteOS-M | — | ❌ 不适用（不适合本运行时） |

`PlatformSupportTest` 会在**构建时真实执行**路径、文件系统、时钟、工具链验证——
覆盖绝对/相对路径、两种分隔符形态、越界拒绝。要在某平台确认可用性，
在该机器（或 CI 矩阵）上跑 `./gradlew test` 即可，**无需改代码**。

### Linux 评估结论

Linux 与 Windows/macOS 走同一条 JVM 路线，**无需任何代码改动**即可运行：

- ✅ 构建、测试、运行全通（`./gradlew check`，JDK 17）
- ✅ 路径语义原生匹配 —— 内置工具的路径归一化按 POSIX 语义实现，Linux/macOS 是"原生正确"路径
- ✅ 文件系统、时钟、环境变量、HTTP 工具均与桌面其它两端口径一致
- ✅ 无 Android 依赖、无平台特权要求
- ⚠️ `shell.run` **内置但默认关闭**（见「进程执行」一节）——这是安全默认，非平台限制；
  Linux/macOS 上开启后走 `sh -c`，Windows 走 `cmd /c`，三端行为一致

### 若要在 iOS / OpenHarmony 上运行

以下工作**未完成**，此处明确列出供后续评估，避免误以为当前产物可直接移植。

**通用前提**：把构建从 `kotlin("jvm")` 改为 `kotlin("multiplatform")`，源集按
`commonMain`（现接口层 + 引擎，已是零平台类型）/ `jvmMain`（现实现原样搬）/ `darwinMain` / `linuxMain` 拆分。

**iOS**（基础工作约 2-4 周）

1. `darwinMain` 实现 `HarnessFileSystem`（`NSFileManager` + `NSData`）与 `HarnessClock`（`NSDate`）
2. HTTP 引擎由 CIO 换为 `ktor-client-darwin`
3. 打包 XCFramework，经 Swift `async/await` 或 Objective-C 桥接调用
4. **必须在产品层接受的能力边界**：
   - `ProcessBuilder` 不可用 → **无法执行外部命令**（Agent 最常用的能力）
   - 动态代码加载被禁 → **插件体系失效**
   - 文件系统沙箱隔离 → 只能读写 App 自己的沙箱
   - 后台执行严格受限 → 长任务会被挂起
   - 结论：iOS 上得到的是"沙箱内的 ReAct 客户端"，而非桌面等价物

**OpenHarmony 标准系统**（先做 3 天可行性探针，再决定是否投入）

1. 探针须先验证三件事：Kotlin/Native `linuxArm64` 产物能否被 NAPI 加载；
   无 JVM 环境下 KN 运行时（TLS / 线程 / 内存模型）是否正常；`Dispatchers.Default` 能否工作
2. 交叉编译工具链（Kotlin/Native **没有官方 OpenHarmony 支持**，需自建）
3. `HarmonyHarnessFileSystem`（OH 文件 API 或 POSIX）
4. NAPI 桥接层 + ArkTS 侧宿主
5. HTTP 无官方 Ktor 引擎 —— 需自实现或用 cinterop 调系统网络 API
6. 风险：上述任一条不通则整条路线不成立，故**先探针后投入**

**鸿蒙 LiteOS-M 不适用**：它面向 MCU / KB 级内存的 IoT 设备，C 语言开发，
连 JVM 都无法运行，更承载不了 Kotlin/Native 运行时。**若目标是"让 Agent 控制鸿蒙设备"，
正确解法不是移植运行时**，而是让 Harness 运行在手机 / 平板 / PC 侧，把设备当作被控端
（经 ACP 之类的协议下沉受控指令）。

### 迁移到更多平台的实际工作量

抽象层已就位，平台相关调用很少 —— 这是当初把接口切在这个位置的价值：

| 位置 | 平台调用点 |
|---|---|
| `harness/` 接口层 | 0（仅注释中提及） |
| `harness/jvm/` 实现层 | 16 |
| `harness/engine` + `harness/tool` | 4 |
| `com.mengpaw.kernel.llm` 模型层 | 13 |
| **合计** | **~33 处 / 13 类 API** |

**但真正的缺口不在"接口够不够"，而在"接口有没有接上"**：`com.mengpaw.kernel` 核心子集里
仍有约 84 处平台调用**直接**访问 `java.io.File` / `System.currentTimeMillis` 而未经注入。
这部分在 JVM 三端上完全无碍（所以 PC 支持成立），但要编译到 iOS/鸿蒙则必须先接通——
工作量约 2-4 天，且不依赖任何平台决策，是任何多平台化路线的前置步骤。

## 进程执行（`shell.run`）

这是 Harness 里唯一"能对宿主动手"的能力，因此**内置但默认关闭**：工具对模型可见
（提示词会列出，并标注「默认关闭」），但调用一律被拒，并返回**开启指引**——
模型据此知道该向用户说明理由、请求开启，而不是反复重试或假装没有这个能力。

**开启方式（三选一，按推荐度）**

```kotlin
// ① 代码开启 — 推荐
val tools = BuiltinTools.registry(env)       // shell.run 已注册, 此时为关闭态
ShellRunTool.enable(tools, env = env)        // 显式开启

// ② 加一道确认门 — 开启且每次执行都需用户批准
val tools = ToolRegistry(env, confirmGate = env.confirmGate).also {
    it.register(ShellRunTool.enabled(env))
}
// 无 UI 宿主 (CLI/CI/后台) 的 DenyAllConfirmGate 会让它永久拒绝 —— 这是 fail-closed 的正确表现

// ③ 环境变量 — 适合容器/CI
// MENGPaw_ALLOW_SHELL=true
```

**为什么不内置"Agent 自助开启"的元工具**：那样模型可以自行提权，安全开关就形同虚设。
Agent 若需要该能力，应**向用户说明理由并请用户开启**——这是设计意图，不是缺失。

**开启后的保护**：危险命令模式拦截（删根 / 格式化 / fork bomb / 远程脚本管道执行 / 关机等）、
60s 超时、20k 输出截断、工作目录锚定 `baseDir`。注意这是**兜底而非沙箱**——
真正的隔离应交给容器或系统级沙箱。

## 设计取舍

这是**库**而不是框架，几处刻意的克制：

- **不替宿主决定存储**：`Conversation` 只要求"取历史 / 加消息"，落盘形态由宿主决定。
- **危险能力默认关闭**：`shell.run` 内置但需显式开启（详见上一节）；内置的文件工具全部限制在 `baseDir` 内（路径穿越拒绝）。
- **确认门是可选第二道防线**：`ToolRegistry` 默认不做确认——工具自身的开关才是主门。
  需要逐次批准的宿主显式传入 `confirmGate`。这样无 UI 环境不会因为"无人可问"而让
  已开启的工具永久不可用。
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
