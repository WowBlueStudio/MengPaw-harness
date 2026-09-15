// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.engine

import com.mengpaw.harness.HarnessToolInvoker
import com.mengpaw.harness.HarnessToolRequest
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.LoopDetector
import com.mengpaw.kernel.llm.ReActParser
import com.mengpaw.kernel.llm.ReActResponse
import com.mengpaw.kernel.llm.ToolCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 会话记忆 — 循环的最小持久化契约。
 *
 * 刻意只要求"取历史 / 加消息"两件事: 文件落盘、多会话管理、压缩归档都是宿主的事,
 * 库不该替宿主决定存储形态。默认用 [InMemoryConversation] 即可跑起来;
 * 需要持久化的宿主实现本接口对接自己的存储 (如 SQLite / 文件 / Redis)。
 */
interface Conversation {
    /** 当前全部消息 — 传给模型的历史 (role/content 对)。 */
    fun messages(): List<Pair<String, String>>

    /** 追加一条消息。 */
    fun add(role: String, content: String)

    /** 清空 — 开始新会话时调用。 */
    fun clear()
}

/** 内存会话 — 开箱即用的默认实现 (进程内, 不落盘)。 */
class InMemoryConversation : Conversation {
    private val store = mutableListOf<Pair<String, String>>()

    override fun messages(): List<Pair<String, String>> = store.toList()

    override fun add(role: String, content: String) { store.add(role to content) }

    override fun clear() { store.clear() }
}

/** 循环过程事件 — 供宿主做 UI 渲染 / 日志 / 观测。全部为可选回调。 */
sealed interface StepEvent {
    data class Thought(val step: Int, val text: String) : StepEvent
    data class Action(val step: Int, val name: String, val input: String) : StepEvent
    data class Observation(val step: Int, val name: String, val output: String, val success: Boolean) : StepEvent
}

/** 一次 ReAct 运行的结果。 */
data class AgentResult(
    /** 最终答复文本 (正常完成时) 或终止说明 (失败时)。 */
    val answer: String,
    /** 是否正常完成 (false = 步数耗尽 / 空响应 / 循环检测等异常终止)。 */
    val completed: Boolean,
    /** 实际执行的步数。 */
    val steps: Int,
    /** 终止原因; 正常完成为 null。 */
    val terminationReason: String? = null
)

/**
 * ReAct 循环引擎 — 本库的核心。
 *
 * 一个**自包含**的 ReAct 实现: 不依赖任何特定平台、不依赖特定工具形态、不依赖特定模型。
 * 它只做一件事——可靠地把"思考/行动/观察"循环跑到底, 并在模型行为异常时体面收场。
 *
 * 与常见 toy 实现的差别都在异常处理上 (这些是真实跑生产 Agent 才会遇到的坑):
 * - **空响应重试**: 部分供应商偶发返回空流 (SSE 零增量); 直接入库空消息会污染历史,
 *   故此处不入库、重试一次, 仍空则明确报错终止。
 * - **循环检测**: 模型反复输出同一 Action 时会空转到步数耗尽; [LoopDetector] 提前终止。
 * - **自适应步数扩展**: 接近上限但仍在有效进展 (无连续失败) 时自动放宽 1.5 倍,
 *   避免长任务被硬上限打断; 有失败累积则不放宽 (防放大成本)。
 * - **退化输出拦截**: 模型卡在重复标记 (如 `<Action><Action>...`) 时不当作最终答案。
 * - **观测不可信**: Observation 作为数据回灌, 提示词层已声明其非指令性。
 *
 * @param llmProvider 模型接入 (本库自带 [com.mengpaw.kernel.llm.AdaptiveLlmProvider])
 * @param toolInvoker 工具执行入口 (可用 [com.mengpaw.harness.tool.BuiltinTools.registry])
 * @param conversation 会话记忆; 默认内存实现
 * @param systemPrompt 系统提示词; 传 null 用 [PromptBuilder] 依工具清单自动生成
 * @param maxSteps 单次运行的最大轮数
 * @param toolTimeoutMs 单个工具执行超时 (防止坏工具永久挂起)
 * @param maxParallelTools 单批并行工具上限 (防止模型一次吐几十个 Action 击穿上游)
 * @param streaming 是否请求流式输出 (onDelta 有值时才真正生效)
 * @param clock 时间源 — 测试可注入假时钟
 */
class ReActEngine(
    private val llmProvider: LlmProvider,
    private val toolInvoker: HarnessToolInvoker,
    private val conversation: Conversation = InMemoryConversation(),
    systemPrompt: String? = null,
    private val maxSteps: Int = 20,
    private val toolTimeoutMs: Long = 60_000L,
    private val maxParallelTools: Int = 8,
    private val streaming: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val parser = ReActParser()

    /** 生效的系统提示词 — 未显式提供时按工具清单自动生成。 */
    val effectiveSystemPrompt: String =
        systemPrompt ?: PromptBuilder.buildSystemPrompt(toolInvoker)

    /** 当前会话历史 (只读快照)。 */
    fun history(): List<Pair<String, String>> = conversation.messages()

    /** 开始新会话 — 清空历史 (engine 实例可复用)。 */
    fun newConversation() = conversation.clear()

    /**
     * 运行一次 ReAct 任务。
     *
     * @param task 用户任务
     * @param onStep 每轮事件回调 (Thought / Action / Observation)
     * @param onDelta 流式正文增量
     * @param onReasoning 思维链增量 (与正文分流, 不混入 onDelta)
     * @param maxStepsOverride 本次运行覆盖最大步数
     */
    suspend fun run(
        task: String,
        onStep: ((StepEvent) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
        maxStepsOverride: Int? = null
    ): AgentResult {
        conversation.add("user", task)

        val originalMax = maxStepsOverride ?: maxSteps
        var effectiveMax = originalMax
        var extended = false

        var step = 0
        var consecutiveFailures = 0
        var emptyResponses = 0
        var consecutiveThoughtOnly = 0
        val loopDetector = LoopDetector()

        while (step < effectiveMax) {
            // ── 自适应扩展: 进展顺利且接近上限时放宽 (有失败累积则不放宽) ──
            if (!extended && step >= effectiveMax * 0.75 && consecutiveFailures == 0) {
                val extendTo = minOf((effectiveMax * 1.5).toInt(), originalMax * 2)
                if (extendTo > effectiveMax) { effectiveMax = extendTo; extended = true }
            }

            val messages = buildMessages()
            val text = try {
                callLlm(messages, onDelta, onReasoning)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "调用模型失败: ${e.message ?: e::class.simpleName ?: "unknown"}"
                conversation.add("assistant", msg)
                return AgentResult(msg, completed = false, steps = step, terminationReason = "llm_error")
            }

            // ── 空响应防御: 不入库、重试一次; 仍空则明确终止 ──
            if (text.isBlank()) {
                emptyResponses++
                if (emptyResponses >= 2) {
                    val msg = "模型连续返回空响应, 已终止。请检查模型/端点是否正常。"
                    return AgentResult(msg, completed = false, steps = step, terminationReason = "empty_response")
                }
                continue
            }
            emptyResponses = 0

            val parsed = parser.parse(text)

            // ── 退化输出: 模型卡在重复标记, 不当作最终答案 ──
            if (parsed.isFinal && parser.isDegenerateOutput(parsed.thought)) {
                val msg = "模型输出异常 (检测到重复标记/格式退化), 请重试该任务。"
                return AgentResult(msg, completed = false, steps = step, terminationReason = "degenerate_output")
            }

            // ── 最终答案: 落历史并返回 ──
            if (parsed.isFinal) {
                val answer = parsed.thought
                conversation.add("assistant", text)
                return AgentResult(stripFinalPrefix(answer), completed = true, steps = step + 1)
            }

            // ── 只思考不行动: 注入继续指令, 连续两次则终止 ──
            if (parsed.needsContinue && parsed.action == null && parsed.actions.isEmpty()) {
                consecutiveThoughtOnly++
                conversation.add("assistant", text)
                if (consecutiveThoughtOnly >= 2) {
                    val msg = "模型连续只思考不行动, 未能完成任务。"
                    return AgentResult(msg, completed = false, steps = step, terminationReason = "no_action")
                }
                conversation.add("system", "请继续。输出 `Action: <工具名>` 与 `Action Input: <参数>`, 或直接给出 `Final Answer:`。")
                step++
                continue
            }
            consecutiveThoughtOnly = 0

            val actions = parsed.actions.ifEmpty { listOfNotNull(parsed.action) }
            if (actions.isEmpty()) {
                // 既无动作也非最终答案 — 视为最终答案兜底 (容错: 不卡住循环)
                conversation.add("assistant", text)
                return AgentResult(stripFinalPrefix(text), completed = true, steps = step + 1)
            }

            parsed.thought.takeIf { it.isNotBlank() }?.let { onStep?.invoke(StepEvent.Thought(step + 1, it)) }
            conversation.add("assistant", text)

            // ── 同批去重 (模型偶发重复输出同一 Action) + 循环检测 ──
            val unique = actions.distinctBy { it.name to it.parameters }
            val looping = unique.firstOrNull { loopDetector.detectLoop("${it.name} ${it.parameters.values.joinToString(" ")}") }
            if (looping != null) {
                val msg = "检测到循环: 模型反复调用 '${looping.name}'。已终止以避免空转。"
                return AgentResult(msg, completed = false, steps = step, terminationReason = "loop_detected")
            }

            val observations = executeTools(unique, onStep, step + 1)
            if (observations.any { !it.second }) consecutiveFailures++ else consecutiveFailures = 0

            conversation.add("system", observations.joinToString("\n\n") { it.first })
            step++
        }

        val msg = "已达最大步数 ($effectiveMax), 任务可能未完成。"
        return AgentResult(msg, completed = false, steps = step, terminationReason = "max_steps")
    }

    /** 单步便捷入口 — 需要完全控制时用 [run]。 */
    suspend fun runOnce(task: String): AgentResult = run(task)

    // ── 内部 ────────────────────────────────────────────────────

    private fun buildMessages(): List<Map<String, String>> = buildList {
        add(mapOf("role" to "system", "content" to effectiveSystemPrompt))
        conversation.messages().forEach { (role, content) -> add(mapOf("role" to role, "content" to content)) }
    }

    private suspend fun callLlm(
        messages: List<Map<String, String>>,
        onDelta: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): String = if (streaming && (onDelta != null || onReasoning != null)) {
        llmProvider.completeStreamingWithMessages(
            messages,
            onToken = { onDelta?.invoke(it) },
            onReasoning = onReasoning
        )
    } else {
        llmProvider.completeWithMessages(messages)
    }

    /** 并行执行一批工具 (有界并发 + 单工具超时), 返回 (Observation 文本, 是否成功) 列表。 */
    private suspend fun executeTools(
        calls: List<ToolCall>,
        onStep: ((StepEvent) -> Unit)?,
        step: Int
    ): List<Pair<String, Boolean>> {
        val semaphore = Semaphore(maxParallelTools.coerceAtLeast(1))
        return coroutineScope {
            calls.map { call ->
                async {
                    semaphore.withPermit {
                        val input = call.parameters["raw"]
                            ?: call.parameters.values.joinToString(" ").trim()
                        onStep?.invoke(StepEvent.Action(step, call.name, input))

                        val (output, success) = try {
                            val result = withTimeoutOrNull(toolTimeoutMs) {
                                toolInvoker.invoke(
                                    HarnessToolRequest(
                                        name = call.name,
                                        flags = if (input.isBlank()) emptyMap()
                                        else mapOf(HarnessToolRequest.RAW_KEY to input)
                                    )
                                )
                            }
                            when {
                                result == null -> "工具 '${call.name}' 执行超时 (${toolTimeoutMs}ms)" to false
                                result.success -> result.output to true
                                else -> (result.output.ifBlank { "工具执行失败" }) to false
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "工具 '${call.name}' 异常: ${e.message ?: e::class.simpleName ?: "unknown"}" to false
                        }

                        onStep?.invoke(StepEvent.Observation(step, call.name, output, success))
                        // Observation 包成标记块 — 提示词已声明其"数据非指令"性质
                        "Observation (${call.name}):\n$output" to success
                    }
                }
            }.awaitAll()
        }
    }

    /** 去掉模型可能带的 `Final Answer:` 前缀 — 返回给宿主的是纯答复。 */
    private fun stripFinalPrefix(text: String): String =
        text.trim().removePrefix("Final Answer:").removePrefix("Final Answer：").trim()
}
