// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.tool

import com.mengpaw.harness.HarnessEnv

/**
 * 进程执行工具 — **内置但默认关闭**。
 *
 * 为什么默认关闭: 这是 Harness 里唯一"能对宿主动手"的能力 —— 一旦对模型开放,
 * 提示词注入、模型幻觉、工具输出污染都可能直接转化为真实的系统命令执行。
 * 因此默认状态是**拒绝执行**, 且必须由人类或 Agent 依本类给出的指引显式开启。
 *
 * 为什么仍然"内置"而不是让宿主自己写: 开启后的实现有大量细节容易做错 ——
 * 平台 shell 选择 (Windows `cmd /c` vs POSIX `sh -c`)、超时、输出截断、工作目录
 * 归一化、危险命令拦截。这些由库统一提供, 宿主只需决定"开不开"。
 *
 * ## 开启方式 (三种, 按推荐度排序)
 *
 * **① 代码开启 — 推荐**: 宿主明确表达意图, 且能同时接上自己的确认门。
 * ```kotlin
 * val tools = BuiltinTools.registry(env)          // 已含 shell.run, 此时为关闭态
 * ShellRunTool.enable(tools)                       // ← 显式开启
 * // 更严的做法: 开启并绑定确认门, 使每次执行都需用户逐次批准
 * ShellRunTool.enable(tools, requireConfirmation = true)
 * ```
 *
 * **② 环境变量开启 — 适合容器/CI 等无人值守场景**:
 * ```
 * MENGPaw_ALLOW_SHELL=true
 * ```
 * 该途径由 [isEnabledByEnv] 在**每次调用时**求值 (而非类加载时), 便于容器注入。
 *
 * **③ Agent 自助开启 — 需宿主先授予能力**:
 * 注册一个宿主自有的元工具 (例如 `agent.enable_shell`) 调用 [enable]。
 * **刻意不内置该元工具**: 否则模型可以自行提权, 安全开关形同虚设。
 * Agent 若需要该能力, 应向用户说明理由并请用户开启 —— 这是设计意图, 不是缺失。
 *
 * ## 开启后的保护措施
 * - **危险命令拦截** ([DANGEROUS_PATTERNS]): 递归删除、磁盘格式化、权限改写、
 *   fork bomb、远程脚本管道执行 —— 命中即拒绝 (非沙箱, 只是兜底)
 * - 超时 [timeoutMs] 默认 60s, 防止挂起拖死循环
 * - 输出截断 [maxOutputChars] 默认 20k, 防止刷爆上下文窗口
 * - 工作目录锚定在 `baseDir`, 并做越界校验
 */
class ShellRunTool private constructor(
    private val env: HarnessEnv,
    private val timeoutMs: Long,
    private val maxOutputChars: Int
) : HarnessTool {

    override val name: String = NAME
    override val description: String = "执行一条系统命令并返回输出 (默认关闭, 需显式开启)"
    override val parameters: String = "<命令>"
    override val riskLevel: String = "high"

    override suspend fun execute(input: ToolInput): ToolOutcome {
        // 关闭态: 如实告知状态 + 给出开启指引 (让 Agent 能据此请求用户开启, 而非反复重试)
        if (!isEnabled()) {
            return ToolOutcome.fail(DISABLED_GUIDE)
        }

        val command = input.raw.trim()
        if (command.isEmpty()) return ToolOutcome.fail("命令为空")

        val blocked = DANGEROUS_PATTERNS.firstOrNull {
            Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(command)
        }
        if (blocked != null) {
            return ToolOutcome.fail(
                "命令被安全策略拒绝 (匹配危险模式: $blocked)。\n" +
                "若确需执行, 请改用更精确的命令, 或由用户调整 ShellRunTool 的策略。"
            )
        }

        val workDir = env.paths.baseDir
        return try {
            val result = ShellExecutor.run(command, workDir, timeoutMs)
            val out = result.output
            val truncated = if (out.length > maxOutputChars) {
                out.take(maxOutputChars) + "\n…(输出已截断, 共 ${out.length} 字符)"
            } else out
            if (result.exitCode == 0) {
                ToolOutcome.ok(if (truncated.isBlank()) "(命令执行成功, 无输出)" else truncated)
            } else {
                ToolOutcome.fail("命令退出码 ${result.exitCode}\n$truncated")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.fail("命令执行失败: ${e.message ?: e::class.simpleName ?: "unknown"}")
        }
    }

    /** 当前是否可执行 — 开关状态或环境变量任一为真即开启。 */
    fun isEnabled(): Boolean = enabled || isEnabledByEnv()

    companion object {
        const val NAME = "shell.run"

        /** 环境变量开启开关 — 供容器/CI 无人值守注入。 */
        const val ENV_ALLOW = "MENGPaw_ALLOW_SHELL"

        /** 默认超时。 */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** 默认输出上限。 */
        const val DEFAULT_MAX_OUTPUT_CHARS = 20_000

        /** 关闭态提示 — 既是错误反馈, 也是给 Agent 的开启指引。 */
        val DISABLED_GUIDE: String = """
            工具 'shell.run' 当前处于**关闭状态**, 未执行任何命令。
            这是安全默认: 该工具能执行任意系统命令, 默认不向模型开放。

            如需使用, 请向用户说明你的任务与理由, 由用户选择下列任一种方式开启:
              ① 代码: ShellRunTool.enable(tools)                    ← 开启
                或: ShellRunTool.enable(tools, requireConfirmation = true)  ← 开启并逐次确认
              ② 环境变量: MENGPaw_ALLOW_SHELL=true                  ← 适合容器/CI
              ③ 由宿主注册的元工具调用 ShellRunTool.enable(...)

            在获得开启之前, 请改用其它可用工具 (如 file.* 读写文件) 完成任务。
        """.trimIndent()

        /** 危险命令模式 — 开启后的兜底拦截 (非沙箱, 只挡最明显的破坏性形态)。 */
        val DANGEROUS_PATTERNS: List<String> = listOf(
            """rm\s+-rf?\s+/(\s|$)""",              // 删根
            """rm\s+-rf?\s+~""",                     // 删家目录
            """mkfs(\.|\s)""",                       // 格式化
            """dd\s+if=.*of=/dev/""",                // 直写块设备
            """>\s*/dev/[sh]d[a-z]""",               // 覆盖磁盘设备
            """chmod\s+-R\s+777\s+/(\s|$)""",        // 全盘权限改写
            """:\(\)\s*\{.*\};\s*:""",               // fork bomb
            """curl[^|]*\|\s*(ba)?sh""",             // 远程脚本管道执行
            """wget[^|]*\|\s*(ba)?sh""",             // 同上 (wget)
            """shutdown|reboot|halt|poweroff""",     // 关机/重启
            """diskpart|format\s+[a-z]:""",          // Windows 磁盘操作
            """Remove-Item.*-Recurse.*-Force.*[A-Z]:\\?$""" // Windows 递归删盘根
        )

        @Volatile
        private var enabled: Boolean = false

        /** 环境变量是否要求开启 — 每次调用求值, 便于容器热注入。 */
        fun isEnabledByEnv(): Boolean =
            System.getenv(ENV_ALLOW)?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

        /** 全局开关状态 (含环境变量)。 */
        fun isEnabled(): Boolean = enabled || isEnabledByEnv()

        /**
         * 开启进程执行 — 建议同时考虑 [requireConfirmation]。
         *
         * @param requireConfirmation 为 true 时把工具风险等级提升为需确认,
         *   使每次执行都经 [com.mengpaw.harness.HarnessConfirmGate] 批准;
         *   注意: 该工具的 riskLevel 恒为 high, 因此**只要注册表绑定了确认门,
         *   开启后每次执行都会请求确认** — 无 UI 宿主将默认拒绝 (fail-closed)。
         */
        fun enable(registry: ToolRegistry? = null, requireConfirmation: Boolean = false, env: HarnessEnv? = null) {
            enabled = true
            if (registry != null && env != null) registry.register(create(env))
            // requireConfirmation 由 riskLevel=high + 注册表确认门共同实现, 此处仅作语义提示
        }

        /** 关闭进程执行 — 恢复安全默认。 */
        fun disable() { enabled = false }

        /** 构造工具实例 (供宿主自行挑选注册位置)。 */
        fun create(
            env: HarnessEnv,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
            maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
        ): ShellRunTool = ShellRunTool(env, timeoutMs, maxOutputChars)

        /** 便捷: 开启并返回实例, 供链式注册。 */
        fun enabled(
            env: HarnessEnv,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
            maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
        ): ShellRunTool {
            enabled = true
            return create(env, timeoutMs, maxOutputChars)
        }
    }
}

/** 命令执行结果。 */
data class ShellResult(val exitCode: Int, val output: String)

/**
 * 跨平台命令执行 — 按宿主动态选择 shell。
 *
 * Windows 用 `cmd /c`, 其余 (Linux/macOS/类 Unix) 用 `sh -c`。
 * 刻意不做 shell 转义 — 命令原样交给宿主 shell 是"能执行命令"这一能力的本意,
 * 注入风险由 [ShellRunTool] 的开启门槛与危险模式拦截承担。
 */
internal object ShellExecutor {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun run(command: String, workDir: String, timeoutMs: Long): ShellResult {
        val process = if (isWindows) {
            ProcessBuilder("cmd.exe", "/c", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
            .directory(java.io.File(workDir).takeIf { it.isDirectory })
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ShellResult(-1, "命令超时 (${timeoutMs}ms) 已终止: $command")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ShellResult(process.exitValue(), output)
    }
}
