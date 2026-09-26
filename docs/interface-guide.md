# Harness 核心接口说明

> 面向要**接入**（实现宿主）或**扩展**（加能力）Harness 核心的开发者与 Agent。
> 权威源：`src/main/kotlin/com/mengpaw/harness/*.kt`（签名以源码为准，本文档解释意图与契约）。
> 变更纪律：改接口必须同步本文件；新增平台能力必须走 `HarnessEnv`，不得新增全局单例。

## 0. 一分钟心智模型

```
宿主 (Android / CLI / 桌面 / Web)
  │  ① 构造 HarnessEnv          ← 平台能力：文件系统/路径/时间/日志/确认门/检查点
  │  ② 构造 HarnessToolInvoker  ← 领域能力：工具怎么执行
  ▼
Harness 核心 (ReAct 循环)
  │  只认接口，不认平台
  ▼
模型输出 "Action" → invoker.invoke() → Observation 回灌 → 下一轮
```

**两条轴必须分开**：平台能力（宿主提供了什么）走 `HarnessEnv`；领域决策（工具是什么）
走 `HarnessToolInvoker`。混在一起会导致"换个工具形态就得改平台实现"。

## 1. 接口一览

| 接口 | 文件 | 一句话 | 谁实现 |
|---|---|---|---|
| `HarnessEnv` | `HarnessEnv.kt` | 平台能力聚合根（值对象，不是接口） | 宿主组装 |
| `HarnessFileSystem` | `HarnessFileSystem.kt` | 读写磁盘的唯一边界 | 宿主/平台实现 |
| `HarnessPathResolver` | `HarnessPathResolver.kt` | 逻辑路径 → 物理路径 | 宿主/默认实现 |
| `HarnessClock` | `HarnessClock.kt` | 时间源（可换假时钟） | 宿主/默认实现 |
| `HarnessLogger` | `HarnessEnv.kt` 内 | 日志出口（对接平台日志） | 宿主 |
| `HarnessConfirmGate` | `HarnessConfirmGate.kt` | 高危操作二次确认（fail-closed） | 宿主（**必做**） |
| `HarnessToolInvoker` | `HarnessToolInvoker.kt` | 工具执行协议 | 宿主 |
| `CheckpointStore` | `HarnessCheckpoint.kt` | 循环状态持久化（断点续跑） | 宿主（内存 / 文件参考实现可直接用） |

参考实现（JVM/Android 通用，可直接用）：`com.mengpaw.harness.jvm.JvmHarnessFileSystem`、
`com.mengpaw.harness.jvm.JvmHarnessClock`、`BaseDirPathResolver`、`ConsoleHarnessLogger`、
`DenyAllConfirmGate`。

## 2. 快速接入（5 步）

```kotlin
// ① 文件系统 + 路径：JVM/Android 直接用默认，自定义宿主自己实现
val env = HarnessEnv(
    fileSystem  = JvmHarnessFileSystem,
    paths       = BaseDirPathResolver(baseDir = "/data/mengpaw"),   // 或 DirectoryNames.ASCII
    clock       = JvmHarnessClock,
    logger      = MyPlatformLogger,          // 对接 Logcat / console / 结构化日志
    confirmGate = MyDialogConfirmGate        // ← 有 UI 就必须实现，否则高危操作全拒
)

// ② 工具执行器：把模型的 Action 路由到你的执行体
class MyToolInvoker : HarnessToolInvoker {
    override suspend fun invoke(request: HarnessToolRequest): HarnessToolResult =
        try {
            HarnessToolResult.ok(executeMyTool(request.name, request.raw))
        } catch (e: CancellationException) {
            throw e                                     // 取消必须向上传播
        } catch (e: Exception) {
            HarnessToolResult.fail(e.message ?: "unknown", errorCode = "TOOL_ERROR")  // 不抛异常
        }
}
```

**最小可用**：只要 `fileSystem` + `paths` 两个参数就能构造 `HarnessEnv`，其余三项都有安全默认。

## 3. 逐接口契约

### 3.1 `HarnessFileSystem`
- 路径一律 `String`；**不承诺路径规范化**（分隔符、相对路径由实现决定）。
- `readText` 失败**抛异常**（不返回空串）；调用方自行 try/catch 决定降级。
- 必须线程安全（循环与并行 worker 并发读写）。
- `size` 不存在返回 `-1`，`lastModified` 不存在返回 `0`。

### 3.2 `HarnessPathResolver`
- `baseDir` **必填无默认**——消除"忘记初始化"导致的静默写错位置。
- `sanitizeSegment` 是所有接收外部 `agentName` 的实现的**必调**步骤（防目录穿越）。
- `agentEvolutionDir(null)` / `agentEvolutionDir("default")` 必须回落 `evolutionDir`（无主归属）。
- `DirectoryNames.CHINESE` 是默认档（与 MengPaw 既有布局逐字一致）；`ASCII` 供对中文路径敏感的宿主。

### 3.3 `HarnessClock`
- `nanoTime` 只用于测间隔，不可当绝对时间。
- `today()` 与宿主时区绑定（记忆按日期分档切文件）。
- 测试注入假时钟可让"30s 超时"类逻辑秒级跑完。

### 3.4 `HarnessConfirmGate` ⚠️ 安全关键
- **无用户可问时必须拒绝**——默认 `DenyAllConfirmGate` 返回 `NO_LISTENER` 且不放行。
- 必须区分 `DENIED`（用户拒绝）/ `NO_LISTENER`（无人可问）/ `TIMEOUT`（超时未决）：
  三者审计语义与对模型的提示措辞不同。
- 任何异常路径视为拒绝（fail-closed），`request` 永不抛异常。
- 只有 `ConfirmDecision.ALLOWED.isAllowed == true` 才放行。
- **执行入口**（MengPaw 侧）：`AgentToolRunner` → `RiskGate.evaluate(..., confirmGate = engine.harnessEnv.confirmGate)`。
  注意只有 **HIGH 级**命令会走到确认门；MID 级（如 `agent.memory.rm`）只查权限等级，
  LOW 级直接放行 —— 写测试时选 HIGH 级命令（`clipboard.clear` / `proc.exec` / `root.*`），
  否则断言"门被调用"会失败。另：表内 HIGH 命令需带 `reason` 才过 `HighRiskCommandGate`。

### 3.5 `HarnessToolInvoker`
- **不抛异常**：失败用 `HarnessToolResult.fail(...)` 表达。唯一例外是 `CancellationException`
  必须原样抛出，否则停止/取消语义失效。
- `HarnessToolResult.success` 决定核心是否把该次调用计入循环失败统计。
- `HarnessToolRequest.raw`：参数纯净规则下的整行透传（路径/URL 不被框架二次切分）。
  多字段结构（JSON）不应拆成多个 flag——见主仓库 `paramFormatError` 门卫教训。
- 实现方自负超时/限流/权限分级；核心不重复做。
- 单批可能并行发起（有界并发），实现须线程安全。

### 3.6 `HarnessEnv`
- 是**值对象**（`data class`），可整体替换（测试注入整套假实现）。
- 不含 `HarnessToolInvoker`——见 §0 两轴分离。
- 新增平台能力时扩展本类，**不要**新增全局单例（否则同进程无法跑两个独立实例）。

### 3.7 `CheckpointStore`（v0.2.0 新增）⚠️ 安全相关
- 定位：平台能力（"循环状态存到哪"），经 `HarnessEnv.checkpoints` 注入 —— 与 `HarnessFileSystem` 同层。
- 三态 `CheckpointStatus`：`RUNNING`（可续）/ `COMPLETED` / `FAILED`（终态）。
  **只有 `RUNNING` 会被 `ReActEngine.run(resume = true)` 接续** —— 终态续跑无意义，
  该重跑还是该丢弃由宿主策略决定。
- 异常契约：`load` 取不到（不存在 / 损坏）返回 `null` 不抛；`save` / `clear` 失败可抛
  —— 引擎会吞掉异常并继续主任务（**检查点不是新的失败点**），异常经 `onCheckpointError` 上报。
- 实现必须线程安全（同会话可能被并发读写）。
- **`sessionId` 用在路径 / 键拼接前必须消毒**：`FileCheckpointStore.pathFor(id)` 是推荐入口
  （点号一并替换，`..` 无法存活）。宿主自建落盘实现必须自行消毒 —— 这是目录穿越防线。
- 参考实现：`InMemoryCheckpointStore`（默认，进程内）/ `FileCheckpointStore`（JSON 落盘，
  通常接 `paths.checkpointDir`；经 `HarnessFileSystem`，零平台类型）。

## 4. 常见任务

**新增一项平台能力**（如"剪贴板""设备信息"）
1. 在 `com.mengpaw.harness` 下建接口，**签名零平台类型**（门禁会拦）；
2. 在 `com.mengpaw.harness.jvm` 下放 JVM/Android 参考实现；
3. 加进 `HarnessEnv`（带默认值，保持向后兼容）；
4. 补测试：至少覆盖"默认实现的安全行为"。
5. 跑 `./gradlew check`（含 `verifyNoPlatformTypes` 门禁）。

**接入一个新宿主（如 CLI）**
1. 先实现 `HarnessConfirmGate`（CLI 用 stdin y/n，超时按拒绝）；
2. `HarnessFileSystem` / `HarnessPathResolver` 用 JVM 默认或自定义；
3. `HarnessToolInvoker` 决定工具形态（命令行进程 / 内置函数表）；
4. 不要为了跑通而把 `confirmGate` 换成"永远允许"——那是安全漏洞。

**接入断点续跑**（v0.2.0）
1. 选存储：进程内恢复用默认 `InMemoryCheckpointStore`；跨进程 / 崩溃恢复用
   `FileCheckpointStore(env.fileSystem, env.paths.checkpointDir)`，写进 `HarnessEnv.checkpoints`；
2. 引擎侧传 `checkpointStore = env.checkpoints`（+ 稳定的 `sessionId`）——不传即不写检查点，行为不变；
3. 恢复入口 `engine.run(task, resume = true)`；只有 `RUNNING` 会被接续；
4. 任务确认完成后 `engine.clearCheckpoint()`，避免下次误续旧状态；
5. 检查点写失败不中断任务，但要接 `onCheckpointError` 让失败可见（默认静默）。

**搬运一个核心模块进来**（B 阶段进行中）
见 `docs/migration-roadmap.md`：一次一个包，搬完立刻跑 `./gradlew check`，绿了才继续。

## 5. 硬约束（违反即构建失败或安全事故）

| 约束 | 强制方式 |
|---|---|
| 核心源码不得 import `java.*` / `javax.*` / `android.*` / `androidx.*` / `dalvik.*` | `verifyNoPlatformTypes` 门禁（**覆盖核心全部子包** `engine`/`tool`，仅排除 `.jvm`） |
| 新建 `.kt`/`.kts` 必须带 SPDX 双许可头 | 项目红线 |
| 禁止 `!!` 强制解包；文件 IO 必须 try/catch | 项目红线 |
| 单文件 ≤ 400 行 | 项目红线 |
| **禁止把 API Key 写进日志/审计/用户可见文本** | 项目红线（唯一安全禁区） |
| 确认门不得"永远允许" | 安全审计 |
| 落盘键（`sessionId`）拼路径前必须消毒 | 安全审计（`FileCheckpointStore` 已内置） |

## 6. 当前状态与未完成项

- ✅ **A 阶段**：抽象层落位（2026-08-21），kernel 已接入注入点，665 内核用例全绿。
- ✅ **抽象层单一事实源**（2026-09-18，v0.2.0）：kernel 内联副本 `com.mengpaw.kernel.harness`
  已删除，宿主统一经 `com.mengpaw.harness.*`；kernel 侧仅留 `KernelHarnessEnv.default()` 适配器
  （承接原 `HarnessEnv.fromKernelGlobals()`）。两副本此前已实测漂移 —— `HarnessToolRequest.ofRaw`
  的空值处理、`DirectoryNames.socket` 目录名两侧不一致。
- ✅ **`llm` 包已搬入**：`com.mengpaw.kernel.llm.*`（`LlmProvider` / `AdaptiveLlmProvider` / SSE /
  `ReActParser` / `LoopDetector` / 限速 / 翻译中间件等 17 文件）归本仓库，kernel 内实现已删除。
  （v0.1.x 文档曾写"`LlmProvider` 尚未搬入" —— 那是过期陈述，本版更正。）
- 🆕 **v0.2.0 能力**：`CheckpointStore` 断点续跑（§3.7）；`verifyNoPlatformTypes` 覆盖面
  从"顶层文件"扩到**核心全部子包**。
- ⏳ **B 阶段剩余**：`cli` / `session` / ReAct 骨架；`AgentEngine` 主循环仍在 kernel，
  `ReActEngine` 尚未接管 MengPaw 主链路（C 阶段）。
- ⚠️ 真实缺口：消息类型仍是 `List<Map<String, String>>`，搬 `session` 时应升级为
  `@Serializable` data class（协议稳定性要求）。
- ⚠️ 门禁边界：只匹配 `import` 语句，全限定引用（如 `System.currentTimeMillis()`）抓不到 ——
  新增平台调用请自觉走 `HarnessEnv` 注入。
- 📌 发布纪律：本仓库 tag / JitPack 发布**需用户明确指令**，不得自行发版。
