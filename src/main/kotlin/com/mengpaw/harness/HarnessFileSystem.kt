// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

/**
 * Harness 平台抽象 — 文件系统。
 *
 * 存在意义: Agent 循环的每一环 (提示词装配、记忆读写、工具结果外存、检查点、审计日志)
 * 最终都要落到磁盘, 而各平台的文件 API 互不兼容。本接口是 harness 核心与宿主文件系统
 * 之间的**唯一边界** — 签名不出现任何 `java.*` / `android.*` 类型, 保证抽象层本身
 * 可被 KMP 各目标编译 (由仓库门禁 `verifyNoPlatformTypes` 强制)。
 *
 * 设计约束:
 * - 路径一律用 [String] 传递 (平台隔离在实现内部), 不引用平台 File 类型。
 * - **不承诺路径规范化语义** — 相对路径解析、分隔符归一由实现自行决定。
 * - 实现必须线程安全: ReAct 循环与并行 worker 会并发读写。
 * - [readText] 失败照常抛异常 (不静默返回空串), 由调用方 try/catch 决定降级策略。
 *
 * 参考实现: `com.mengpaw.harness.jvm.JvmHarnessFileSystem` (JVM/Android 通用)。
 */
interface HarnessFileSystem {

    /** 路径是否存在 (文件或目录)。 */
    fun exists(path: String): Boolean

    /** 路径是否为目录。 */
    fun isDirectory(path: String): Boolean

    /** 读取全文 (UTF-8)。文件不存在或读取失败抛异常, 由调用方处理。 */
    fun readText(path: String): String

    /**
     * 写入全文 (UTF-8), 覆盖已有内容。
     * @param createParentDirs 为 true 时自动创建父目录。
     */
    fun writeText(path: String, content: String, createParentDirs: Boolean = true)

    /** 追加内容 (UTF-8); 文件不存在则创建。 */
    fun appendText(path: String, content: String, createParentDirs: Boolean = true)

    /** 创建目录 (含父目录)。已存在返回 true。 */
    fun mkdirs(path: String): Boolean

    /** 列出目录下的条目名 (不含路径前缀)。目录不存在或非目录返回空列表。 */
    fun list(path: String): List<String>

    /** 删除文件或空目录。不存在返回 false。 */
    fun delete(path: String): Boolean

    /** 文件大小 (字节)。不存在返回 -1。 */
    fun size(path: String): Long

    /** 最后修改时间 (epoch millis)。不存在返回 0。 */
    fun lastModified(path: String): Long

    /** 按文件名前缀/后缀过滤目录条目 — 记忆/归档列表场景高频, 避免调用方反复 list + 过滤。 */
    fun listFiltered(path: String, prefix: String = "", suffix: String = ""): List<String> =
        list(path).filter { it.startsWith(prefix) && it.endsWith(suffix) }
}
