// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

/**
 * Harness 平台抽象 — 工具执行器。
 *
 * 存在意义: ReAct 循环里模型输出的 "Action" 最终要落到某个执行体上, 而执行形态
 * 是**产品决策而非平台细节** — 可以是「工具即 CLI 命令」(命令文本管线)、
 * 原生 function-calling (OpenAI tools)、MCP 远程工具、沙箱子进程, 甚至测试桩。
 *
 * 本接口把「模型想做什么」与「宿主怎么做」解耦: 核心只在循环里调用 [invoke],
 * 具体路由由宿主决定。这同时让核心可脱离任何具体命令体系被测试。
 *
 * 契约:
 * - **不得抛异常** — 执行失败必须以 [HarnessToolResult.success] = false 表达,
 *   否则会把工具错误升级成循环崩溃。唯一例外: 协程取消 (`CancellationException`)
 *   必须原样向上传播, 否则停止语义失效。
 * - 实现方负责自身的超时/限流/安全分级; 核心不重复做。
 * - 线程安全: 单批工具调用会被核心并行发起 (有界并发)。
 */
interface HarnessToolInvoker {

    /** 执行一次工具调用。失败返回, 不抛异常 (取消除外)。 */
    suspend fun invoke(request: HarnessToolRequest): HarnessToolResult

    /**
     * 当前可用的工具列表 — 供系统提示词渲染「可用工具」段落。
     * 返回空列表表示宿主不提供工具发现 (提示词由宿主自行注入)。
     */
    fun listTools(): List<HarnessToolSpec> = emptyList()
}

/**
 * 一次工具调用请求。
 *
 * @param name 工具名 (可含命名空间, 如 `fs.cat`)
 * @param args 位置参数 (已按宿主解析规则拆分)
 * @param flags 显式 flag 参数 (`--key value` 形态); 亦用于承载整行 raw 参数
 * @param sessionId 发起调用的会话 id — 供审计与并发隔离
 * @param agentName 发起调用的 Agent 名 — 供权限分级
 */
data class HarnessToolRequest(
    val name: String,
    val args: List<String> = emptyList(),
    val flags: Map<String, String> = emptyMap(),
    val sessionId: String = "",
    val agentName: String = ""
) {
    /**
     * 整行参数透传 — 参数纯净规则下, 模型给出的整行文本原样交给执行方解释,
     * 避免框架层对参数做二次切分导致路径/URL 被拆坏。
     */
    val raw: String? get() = flags[RAW_KEY]

    companion object {
        /** 整行参数透传的保留键。 */
        const val RAW_KEY = "raw"

        /** 便捷构造 — 单个整行参数 (最常见形态)。 */
        fun ofRaw(
            name: String,
            raw: String,
            sessionId: String = "",
            agentName: String = ""
        ): HarnessToolRequest = HarnessToolRequest(
            name = name,
            flags = if (raw.isBlank()) emptyMap() else mapOf(RAW_KEY to raw),
            sessionId = sessionId,
            agentName = agentName
        )
    }
}

/**
 * 工具执行结果。
 *
 * @param success 是否成功 — 决定核心是否将其计入循环的失败统计
 * @param output 观察文本 (Observation), 原样回灌给模型
 * @param errorCode 失败时的机器可读错误码 (宿主自定义; 供重试策略判定)
 */
data class HarnessToolResult(
    val success: Boolean,
    val output: String,
    val errorCode: String? = null
) {
    companion object {
        fun ok(output: String): HarnessToolResult = HarnessToolResult(true, output)

        fun fail(output: String, errorCode: String? = null): HarnessToolResult =
            HarnessToolResult(false, output, errorCode)
    }
}

/**
 * 工具规格 — 供系统提示词向模型描述可用工具。
 *
 * @param name 工具名 (含命名空间)
 * @param description 一句话功能描述 (会注入提示词, 务必简短)
 * @param signature 用法签名 (如 `fs.cat <路径>`), 可为空
 * @param riskLevel 风险分级标签 (`low`/`mid`/`high`), 供提示词警示与宿主门禁
 */
data class HarnessToolSpec(
    val name: String,
    val description: String = "",
    val signature: String = "",
    val riskLevel: String = "low"
)
