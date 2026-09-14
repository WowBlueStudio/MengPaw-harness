// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

/**
 * Harness 平台抽象 — 时间源。
 *
 * 存在意义: 两重。
 * ① 可移植性 — `System.currentTimeMillis()` / `System.nanoTime()` 是 JVM 专有;
 * ② 可测性 — 超时、退避、循环检测、检查点间隔等逻辑依赖真实时钟, 在测试中无法
 *    稳定复现边界 (如"30s 超时"需要真等 30 秒)。注入后测试可换成可推进的假时钟。
 *
 * 实现必须线程安全且 [nowMillis] / [nanoTime] 单调不倒退 (后者天然单调)。
 */
interface HarnessClock {
    /** 当前时刻 (epoch millis) — 对应 `System.currentTimeMillis()`。 */
    fun nowMillis(): Long

    /** 单调递增纳秒 — 仅用于测量间隔, 不可用于绝对时间。 */
    fun nanoTime(): Long

    /**
     * 本地日期 (yyyy-MM-dd) — 记忆按日期分档切文件, 与宿主时区绑定, 故属平台能力。
     */
    fun today(): String

    /** 当前时刻的 ISO-8601 字符串 (供审计/报告落盘)。 */
    fun nowIso(): String
}
