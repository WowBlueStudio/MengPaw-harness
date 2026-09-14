// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.jvm

import com.mengpaw.harness.HarnessFileSystem

/**
 * [HarnessFileSystem] 的 JVM 实现 — JVM 与 Android 共用。
 *
 * 位于 `.jvm` 子包 (而非接口所在的根包): 本文件引用 `java.io.File`, 属**平台实现**,
 * 不参与跨平台门禁 (`verifyNoPlatformTypes` 只校验接口层)。
 *
 * 异常语义与平台原生一致 — [readText] 失败照常抛, 便于调用方沿用既有 try/catch 策略,
 * 不在抽象层引入第二套错误约定。
 */
object JvmHarnessFileSystem : HarnessFileSystem {

    override fun exists(path: String): Boolean = java.io.File(path).exists()

    override fun isDirectory(path: String): Boolean = java.io.File(path).isDirectory

    override fun readText(path: String): String = java.io.File(path).readText()

    override fun writeText(path: String, content: String, createParentDirs: Boolean) {
        val file = java.io.File(path)
        if (createParentDirs) file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun appendText(path: String, content: String, createParentDirs: Boolean) {
        val file = java.io.File(path)
        if (createParentDirs) file.parentFile?.mkdirs()
        file.appendText(content)
    }

    override fun mkdirs(path: String): Boolean {
        val dir = java.io.File(path)
        return dir.mkdirs() || dir.isDirectory
    }

    override fun list(path: String): List<String> {
        val dir = java.io.File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.list()?.toList() ?: emptyList()
    }

    override fun delete(path: String): Boolean = java.io.File(path).delete()

    override fun size(path: String): Long {
        val file = java.io.File(path)
        return if (file.exists()) file.length() else -1L
    }

    override fun lastModified(path: String): Long {
        val file = java.io.File(path)
        return if (file.exists()) file.lastModified() else 0L
    }
}
