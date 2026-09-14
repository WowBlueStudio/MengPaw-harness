// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

/**
 * Harness 平台抽象 — 高危操作确认门。
 *
 * 存在意义: Agent 循环在执行高风险动作前必须能"暂停并征询用户"。但确认的形态
 * 完全由宿主决定 — 终端 y/n、移动端对话框、Web 端 HTTP 回调、桌面端托盘气泡。
 * 核心不应知道任何一种。
 *
 * 安全契约 (不可让步):
 * - **无用户可问时必须拒绝**, 不得静默放行。后台 worker、无人值守 CLI、测试环境
 *   一律走 [DenyAllConfirmGate]。
 * - 任何异常路径视为拒绝 (fail-closed)。
 * - 必须区分「用户拒绝」与「无人可问」— 二者审计语义与对模型的提示措辞不同。
 */
interface HarnessConfirmGate {

    /**
     * 请求用户确认一项高危操作。**必须挂起直到决出或超时**。
     *
     * @param command 待确认的命令/动作名 (非全文, 供弹窗标题)
     * @param reason 模型声明的高危意图 (可为空)
     * @param riskLabel 风险等级标签 (供 UI 着色)
     * @param timeoutMs 超时上限; 超时必须按拒绝收尾
     * @return 决出结果 — 永不抛异常
     */
    suspend fun request(
        command: String,
        reason: String?,
        riskLabel: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ConfirmDecision

    companion object {
        /** 默认确认超时 — 30s 后按拒绝收尾。 */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}

/** 确认门决出结果 — 区分"被拒"与"无人可问", 二者审计语义不同。 */
enum class ConfirmDecision {
    /** 用户明确允许。 */
    ALLOWED,

    /** 用户明确拒绝。 */
    DENIED,

    /** 无任何监听者 (后台 worker / 无 UI 宿主) — 安全默认, 不予执行。 */
    NO_LISTENER,

    /** 超时未决 — 安全默认, 不予执行。 */
    TIMEOUT;

    /** 是否放行执行。 */
    val isAllowed: Boolean get() = this == ALLOWED
}

/**
 * 默认确认门 — **一律拒绝** (fail-closed)。
 *
 * 无 UI 宿主 (后台 worker / 无人值守 CLI / 测试) 必须使用本实现,
 * 不得为了"跑得通"而静默放行高危操作。
 */
object DenyAllConfirmGate : HarnessConfirmGate {
    override suspend fun request(
        command: String,
        reason: String?,
        riskLabel: String,
        timeoutMs: Long
    ): ConfirmDecision = ConfirmDecision.NO_LISTENER
}
