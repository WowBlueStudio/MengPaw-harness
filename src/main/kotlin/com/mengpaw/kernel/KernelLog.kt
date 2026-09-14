// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

/**
 * Harness 日志出口 — 可注入的全局日志汇。
 *
 * 定位: 本对象**取代**原 kernel 内的同名单例 (随核心搬入 harness 仓库)。
 * 搬到独立仓库的意义在于: 它从此是一个**可替换**的日志汇, 而不是宿主专属实现 —
 * Android 注入 Logcat 适配器, CLI/JVM 用默认控制台实现, 测试可静音。
 *
 * 设计取向 (刻意从简):
 * - 全局单例是**有意保留**的: 日志属于横切关注点, 逐层注入会把日志参数渗透进
 *   每一个函数签名, 收益远小于噪音。若需完全隔离 (如同进程多实例不同日志出口),
 *   用 [setLogger] 在启动时替换, 而非改造调用链。
 * - 跨平台: 接口只依赖 `String`, 无平台类型; 默认实现用标准输出, 各平台通用。
 *
 * 安全提醒: **API Key 等凭据绝不能经此输出** (凭据是唯一安全禁区)。
 */
interface Logger {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun e(tag: String, msg: String)
}

/** 控制台日志 — JVM/桌面/CLI 默认实现, 零依赖。 */
class ConsoleLogger : Logger {
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")
    override fun w(tag: String, msg: String) = println("W/$tag: $msg")
    override fun i(tag: String, msg: String) = println("I/$tag: $msg")
    override fun e(tag: String, msg: String) = println("E/$tag: $msg")
}

/** 静默日志 — 测试用 (核心里有意避开断言输出污染)。 */
class SilentLogger : Logger {
    override fun d(tag: String, msg: String) {}
    override fun w(tag: String, msg: String) {}
    override fun i(tag: String, msg: String) {}
    override fun e(tag: String, msg: String) {}
}

/**
 * 全局日志汇 — 宿主启动时经 [setLogger] 注入平台实现。
 * 默认 [ConsoleLogger], 因此在任何 JVM/Android 环境都能直接工作。
 */
object KernelLog {
    @Volatile
    var logger: Logger = ConsoleLogger()
        private set

    fun setLogger(l: Logger) { logger = l }

    fun d(tag: String, msg: String) = logger.d(tag, msg)
    fun w(tag: String, msg: String) = logger.w(tag, msg)
    fun i(tag: String, msg: String) = logger.i(tag, msg)
    fun e(tag: String, msg: String) = logger.e(tag, msg)
}
