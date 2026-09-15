// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.tool

import com.mengpaw.harness.ConfirmDecision
import com.mengpaw.harness.HarnessConfirmGate
import com.mengpaw.harness.HarnessEnv
import com.mengpaw.harness.HarnessToolInvoker
import com.mengpaw.harness.HarnessToolRequest
import com.mengpaw.harness.HarnessToolResult
import com.mengpaw.harness.HarnessToolSpec

/**
 * 一个可被 Agent 调用的工具 — Harness 的工具协议单元。
 *
 * 设计要点:
 * - **元数据与执行器同体**: [name]/[description]/[parameters] 用于渲染系统提示词,
 *   使模型知道有哪些工具可用; [execute] 是实际行为。分开定义会让二者漂移。
 * - **[execute] 不得抛异常**: 一切失败以 [ToolOutcome] 表达。框架会把输出作为
 *   Observation 回灌给模型, 异常会中断整个循环 (唯一例外: 协程取消需原样传播)。
 * - **参数是位置参数文本**: 遵循 ReAct 的 `Action Input: <单行>` 形态。需要结构化
 *   参数的工具应在实现内自行解析 (如 [BuiltinTools] 的 `key=value` 约定)。
 */
interface HarnessTool {

    /** 工具名 — 模型调用时使用的标识 (约定 `命名空间.动作`, 如 `file.read`)。 */
    val name: String

    /** 一句话说明 — 会注入系统提示词, 务必简短精确 (模型据此决定是否调用)。 */
    val description: String

    /** 参数签名 (如 `<路径> [起始行]`), 供提示词展示; 无参工具留空。 */
    val parameters: String get() = ""

    /**
     * 风险等级标签 — 供提示词警示与宿主门禁。
     * `low` 只读/无副作用; `mid` 写文件等可逆副作用; `high` 不可逆或影响系统。
     */
    val riskLevel: String get() = "low"

    /** 执行工具。返回失败而非抛异常 (协程取消除外)。 */
    suspend fun execute(input: ToolInput): ToolOutcome
}

/** 工具调用输入 — 已按空白切分的位置参数 + 原始整行。 */
data class ToolInput(
    /** 按空白切分后的位置参数。 */
    val args: List<String>,
    /** 模型给出的原始整行参数 (未经切分) — 需要原样透传时使用 (如写文件的正文)。 */
    val raw: String
) {
    /** 第 [index] 个位置参数, 缺失返回 null。 */
    fun arg(index: Int): String? = args.getOrNull(index)

    /** 必填位置参数 — 缺失时由调用方 [require] 报错。 */
    fun require(index: Int, name: String): String =
        arg(index) ?: throw ToolArgsException("缺少必填参数 [$name] (第 ${index + 1} 个)")

    companion object {
        /** 便捷构造 — 由整行文本切分。 */
        fun of(raw: String): ToolInput =
            ToolInput(raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }, raw.trim())
    }
}

/** 参数解析失败 — 工具内部抛出, 由 [ToolRegistry] 统一转为失败结果。 */
class ToolArgsException(message: String) : Exception(message)

/** 工具执行结果 — 成功与否 + 回灌给模型的观察文本。 */
data class ToolOutcome(
    val success: Boolean,
    val output: String
) {
    companion object {
        fun ok(output: String) = ToolOutcome(true, output)
        fun fail(output: String) = ToolOutcome(false, output)
    }
}

/**
 * 工具注册表 — 同时是 [HarnessToolInvoker] 的通用实现。
 *
 * 这一层是 Harness 与"工具形态"的解耦点: MengPaw 用 CLI 命令当工具, 而本注册表
 * 接受任何 [HarnessTool] 实现。宿主既可直接注册 Kotlin 闭包, 也可用
 * [fromCommandRunner] 把外部命令体系 (CLI/shell/MCP) 适配进来。
 *
 * 线程安全: [register] 与 [invoke] 都可能被并发调用 (单批工具并行执行), 内部用
 * 并发映射保护。
 *
 * @param env 平台环境 — 工具经此访问文件系统与确认门 (而非直接碰平台 API)
 * @param confirmGate 高危工具确认门; 传 null 表示不设门禁 (调用方自担)
 */
class ToolRegistry(
    private val env: HarnessEnv,
    private val confirmGate: HarnessConfirmGate? = null
) : HarnessToolInvoker {

    /**
     * 工具表 — 以不可变 Map 快照 + 写时替换实现无锁读。
     *
     * 刻意不用 `java.util.concurrent.ConcurrentHashMap`: 那是 JVM 专有类型,
     * 会阻断 KMP 迁移。register 频率极低 (启动期), invoke 频率高, 该模式正合适。
     */
    @Volatile
    private var tools: Map<String, HarnessTool> = emptyMap()

    /** 注册一个工具; 同名覆盖 (便于宿主替换内置实现)。 */
    @Synchronized
    fun register(tool: HarnessTool): ToolRegistry {
        tools = tools + (tool.name to tool)
        return this
    }

    /** 批量注册。 */
    fun registerAll(vararg list: HarnessTool): ToolRegistry {
        list.forEach { register(it) }
        return this
    }

    /** 已注册工具名 (排序后, 供提示词渲染稳定输出)。 */
    fun toolNames(): List<String> = tools.keys.sorted()

    /** 已注册工具定义。 */
    fun tools(): List<HarnessTool> = toolNames().mapNotNull { tools[it] }

    /** 是否存在指定工具。 */
    fun contains(name: String): Boolean = tools.containsKey(name)

    /** 提示词用工具清单。 */
    override fun listTools(): List<HarnessToolSpec> = tools().map {
        HarnessToolSpec(name = it.name, description = it.description, signature = it.parameters, riskLevel = it.riskLevel)
    }

    /**
     * 执行工具调用 — [HarnessToolInvoker] 实现。
     *
     * 流程: 查表 → 高危确认 (若配置了门) → 执行 → 异常兜底。
     * **永不抛异常** (协程取消除外), 保证循环不因单个工具崩溃而中断。
     */
    override suspend fun invoke(request: HarnessToolRequest): HarnessToolResult {
        val tool = tools[request.name]
            ?: return HarnessToolResult.fail(
                "未知工具 '${request.name}'。可用工具: ${toolNames().joinToString(", ").ifEmpty { "(无)" }}",
                errorCode = ERR_UNKNOWN_TOOL
            )

        // 高危工具二次确认 (fail-closed: 无用户可问即拒绝)
        if (tool.riskLevel == "high") {
            val gate = confirmGate ?: env.confirmGate
            val decision = gate.request(
                command = tool.name,
                reason = request.raw,
                riskLabel = "高危"
            )
            if (decision != ConfirmDecision.ALLOWED) {
                return HarnessToolResult.fail(
                    "高危工具 '${tool.name}' 未获确认 (${decision.name}), 已阻止。",
                    errorCode = ERR_NOT_CONFIRMED
                )
            }
        }

        val input = ToolInput.of(request.raw ?: request.args.joinToString(" "))
        val outcome = try {
            tool.execute(input)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须向上传播, 否则停止语义失效
        } catch (e: ToolArgsException) {
            ToolOutcome.fail("参数错误: ${e.message}")
        } catch (e: Exception) {
            ToolOutcome.fail("工具执行异常: ${e.message ?: e::class.simpleName ?: "unknown"}")
        }

        return if (outcome.success) {
            HarnessToolResult.ok(outcome.output)
        } else {
            HarnessToolResult.fail(outcome.output, errorCode = ERR_EXECUTION_FAILED)
        }
    }

    companion object {
        const val ERR_UNKNOWN_TOOL = "UNKNOWN_TOOL"
        const val ERR_NOT_CONFIRMED = "NOT_CONFIRMED"
        const val ERR_EXECUTION_FAILED = "TOOL_FAILED"

        /**
         * 由任意命令执行函数构造注册表 — 适配 CLI/shell/MCP 等外部工具体系。
         *
         * 每个名字对应一个 [HarnessTool]; 执行体是 [runner] (接收整行参数, 返回文本)。
         * 这是把"外部命令集合"接入 Harness 的最短路径。
         */
        fun fromCommandRunner(
            env: HarnessEnv,
            specs: List<HarnessToolSpec>,
            runner: suspend (name: String, rawInput: String) -> ToolOutcome,
            confirmGate: HarnessConfirmGate? = null
        ): ToolRegistry {
            val registry = ToolRegistry(env, confirmGate)
            specs.forEach { spec ->
                registry.register(object : HarnessTool {
                    override val name = spec.name
                    override val description = spec.description
                    override val parameters = spec.signature
                    override val riskLevel = spec.riskLevel
                    override suspend fun execute(input: ToolInput) = runner(spec.name, input.raw)
                })
            }
            return registry
        }
    }
}
