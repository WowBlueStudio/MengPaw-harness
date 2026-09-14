# Harness 核心接口说明

> 面向要**接入**（实现宿主）或**扩展**（加能力）Harness 核心的开发者与 Agent。
> 权威源：`src/main/kotlin/com/mengpaw/harness/*.kt`（签名以源码为准，本文档解释意图与契约）。
> 变更纪律：改接口必须同步本文件；新增平台能力必须走 `HarnessEnv`，不得新增全局单例。

## 0. 一分钟心智模型

```
宿主 (Android / CLI / 桌面 / Web)
  │  ① 构造 HarnessEnv          ← 平台能力：文件系统/路径/时间/日志/确认门
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

**迁移一个核心模块进来**（B 阶段进行中）
见 `docs/migration-roadmap.md`：一次一个包，搬完立刻跑 `./gradlew check`，绿了才继续。

## 5. 硬约束（违反即构建失败或安全事故）

| 约束 | 强制方式 |
|---|---|
| 核心源码不得 import `java.*` / `javax.*` / `android.*` / `androidx.*` / `dalvik.*` | `verifyNoPlatformTypes` 门禁 |
| 新建 `.kt`/`.kts` 必须带 SPDX 双许可头 | 项目红线 |
| 禁止 `!!` 强制解包；文件 IO 必须 try/catch | 项目红线 |
| 单文件 ≤ 400 行 | 项目红线 |
| **禁止把 API Key 写进日志/审计/用户可见文本** | 项目红线（唯一安全禁区） |
| 确认门不得"永远允许" | 安全审计 |

## 6. 当前状态与未完成项

- ✅ A 阶段：抽象层落位（本仓库），MengPaw kernel 已接入注入点，663 用例全绿。
- ⏳ B 阶段：核心逻辑搬运中（`llm` 纯逻辑 / `cli` / `session` 优先）。
- ⚠️ 已知缺口：`LlmProvider` 尚未搬入；当前消息类型为 `List<Map<String, String>>`，
  搬入时应升级为 `@Serializable` data class。
- 📌 发布纪律：本仓库 tag / JitPack 发布**需用户明确指令**，不得自行发版。
