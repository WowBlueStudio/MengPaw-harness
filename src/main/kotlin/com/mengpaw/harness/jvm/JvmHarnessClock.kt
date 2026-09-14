// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.jvm

import com.mengpaw.harness.HarnessClock

/** [HarnessClock] 的 JVM 实现 — 使用 java.time (Android API 26+ / JVM 均可用)。 */
object JvmHarnessClock : HarnessClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
    override fun today(): String = java.time.LocalDate.now().toString()
    override fun nowIso(): String = java.time.OffsetDateTime.now().toString()
}
