// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

import com.mengpaw.harness.jvm.JvmHarnessClock
import com.mengpaw.harness.jvm.JvmHarnessFileSystem

/**
 * Harness 平台环境聚合 — 核心向宿主索取的**全部**平台能力清单。
 *
 * 除本聚合暴露的能力外, harness 核心不直接接触任何平台 API。宿主在启动时构造
 * 一个实例并注入引擎, 之后所有路径 / IO / 时间 / 日志 / 确认都经此路由。
 *
 * 为什么是聚合而非散注入: 引擎构造参数会随能力增加而膨胀, 逐个追加会让构造器
 * 变成参数垃圾场; 聚合成一个值对象后, 新增平台能力不破坏既有注入签名。
 *
 * **不含 [HarnessToolInvoker]** — 工具执行属**领域能力**而非平台能力: 它承载的是
 * "工具是什么"的产品决策 (CLI / function-calling / MCP), 不是"平台提供了什么"。
 * 工具执行经独立参数注入, 保持两轴解耦。
 *
 * @param fileSystem 文件系统
 * @param paths 逻辑路径解析
 * @param clock 时间源
 * @param logger 日志出口
 * @param confirmGate 高危确认门; 无 UI 宿主应传 [DenyAllConfirmGate]
 */
data class HarnessEnv(
    val fileSystem: HarnessFileSystem,
    val paths: HarnessPathResolver,
    val clock: HarnessClock = JvmHarnessClock,
    val logger: HarnessLogger = ConsoleHarnessLogger,
    val confirmGate: HarnessConfirmGate = DenyAllConfirmGate
) {
    companion object {
        /**
         * 最简组装 — JVM/Android 宿主的默认形态 (本地文件系统 + 控制台日志 + 拒绝式确认门)。
         *
         * **不含确认门实现**: 需要弹窗确认的宿主必须自行注入
         * [confirmGate]; 默认的 [DenyAllConfirmGate] 会让所有高危操作被拒 —
         * 这是刻意的安全默认, 不是缺陷。
         */
        fun jvmDefault(
            baseDir: String,
            confirmGate: HarnessConfirmGate = DenyAllConfirmGate,
            logger: HarnessLogger = ConsoleHarnessLogger,
            names: BaseDirPathResolver.DirectoryNames = BaseDirPathResolver.DirectoryNames.CHINESE
        ): HarnessEnv = HarnessEnv(
            fileSystem = JvmHarnessFileSystem,
            paths = BaseDirPathResolver(baseDir, names = names),
            clock = JvmHarnessClock,
            logger = logger,
            confirmGate = confirmGate
        )
    }
}

/**
 * Harness 日志出口。
 *
 * 与平台日志系统 (Android Logcat / 浏览器 console / 结构化日志框架) 对接的唯一点;
 * 核心内部只经此输出, 不直接打印 —— 否则日志会绕过宿主的采集与脱敏策略。
 *
 * 安全提醒: **实现方须确保日志不落 API Key 等凭据** (凭据是唯一安全禁区)。
 */
interface HarnessLogger {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun e(tag: String, msg: String)
}

/** 控制台日志 — JVM/桌面/CLI 默认, 零依赖。 */
object ConsoleHarnessLogger : HarnessLogger {
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")
    override fun w(tag: String, msg: String) = println("W/$tag: $msg")
    override fun i(tag: String, msg: String) = println("I/$tag: $msg")
    override fun e(tag: String, msg: String) = println("E/$tag: $msg")
}
