// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.examples

import com.mengpaw.harness.HarnessEnv
import com.mengpaw.harness.HarnessToolRequest
import com.mengpaw.harness.tool.BuiltinTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 三端 (Windows / macOS / Linux) 运行时验证 — 在**当前实际所在平台**执行。
 *
 * 本库的 PC 支持路线是 JVM: 桌面三端各有官方 JDK 17, 因此同一份产物在三端均可运行。
 * 该测试把"可用"从声明变成证据 — 每次构建都会在 CI/开发机上真实跑一遍路径、
 * 文件系统、时间与工具链, 而非仅靠文档承诺。
 *
 * 若要覆盖其它平台, 在对应 OS 的机器 (或 CI 矩阵) 上跑 `./gradlew test` 即可 —
 * 无需改代码 (JVM 路线的价值所在)。
 *
 * 说明: iOS / OpenHarmony 不在本测试覆盖范围 — 那需要 Kotlin/Native 交叉编译,
 * 工作量与能力边界见 README「平台支持」一节。
 */
class PlatformSupportTest {

    private val osName: String = System.getProperty("os.name").orEmpty()
    private val osArch: String = System.getProperty("os.arch").orEmpty()
    private val isPosix: Boolean = !osName.lowercase().contains("win")

    private fun tempDir(): String =
        java.nio.file.Files.createTempDirectory("harness-platform").toString()

    @Test
    fun `当前平台信息可读且 JDK 版本满足要求`() {
        val javaVersion = System.getProperty("java.version").orEmpty()
        assertTrue("应能读到 OS 名称", osName.isNotBlank())
        assertTrue("应能读到 CPU 架构", osArch.isNotBlank())
        assertTrue("JDK 版本应为 17+: $javaVersion", javaVersion.startsWith("17") || javaVersion.startsWith("21") || javaVersion.startsWith("2"))
    }

    @Test
    fun `路径归一化在各平台均正确且拒绝穿越`() {
        val dir = tempDir()
        try {
            val env = HarnessEnv.jvmDefault(dir)
            val tools = BuiltinTools.registry(env)

            runBlocking {
                // ① 相对路径写入 → 落在 baseDir 内
                val write = tools.invoke(
                    HarnessToolRequest.ofRaw("file.write", "sub/dir/note.txt PC 平台验证")
                )
                assertTrue("相对路径应可写入: ${write.output}", write.success)
                assertTrue(
                    "文件应真实落盘 (正斜杠在 Windows 亦被 File 接受)",
                    java.io.File("$dir/sub/dir/note.txt").exists()
                )

                // ② 读回
                val read = tools.invoke(HarnessToolRequest.ofRaw("file.read", "sub/dir/note.txt"))
                assertTrue(read.success)
                assertTrue("读回内容应一致", read.output.contains("PC 平台验证"))

                // ③ 目录列举与 glob
                val ls = tools.invoke(HarnessToolRequest.ofRaw("file.ls", "sub/dir"))
                assertTrue(ls.success)
                assertTrue("应列出刚写的文件", ls.output.contains("note.txt"))

                val glob = tools.invoke(HarnessToolRequest.ofRaw("file.glob", "*.txt"))
                assertTrue("glob 应能匹配到文件: ${glob.output}", glob.output.contains("note.txt"))
            }
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `时钟与环境变量在各平台可用`() {
        val env = HarnessEnv.jvmDefault(tempDir())
        val now = env.clock.nowMillis()
        assertTrue("时钟应给出合理时间戳", now > 1_600_000_000_000L)
        assertTrue("日期格式应为 yyyy-MM-dd", Regex("""\d{4}-\d{2}-\d{2}""").matches(env.clock.today()))

        runBlocking {
            val tools = BuiltinTools.registry(env)
            val result = tools.invoke(HarnessToolRequest.ofRaw("env.get", ""))
            assertTrue("应能列出环境变量键名", result.success)
        }
    }

    @Test
    fun `工作目录之外的文件在任何路径形态下都不会被触碰`() {
        val dir = tempDir()
        val outside = java.nio.file.Files.createTempDirectory("harness-outside")
        try {
            // 在 base 之外放一个哨兵文件 — 一切越界尝试都不得读到/改动它
            val sentinel = java.io.File(outside.toFile(), "sentinel.txt")
            sentinel.writeText("绝密内容-SENTINEL")
            val env = HarnessEnv.jvmDefault(dir)
            val tools = BuiltinTools.registry(env)

            val escapes = buildList {
                add("../../${outside.toFile().name}/sentinel.txt")
                add("../sentinel.txt")
                add("sub/../../../${outside.toFile().name}/sentinel.txt")
                add(sentinel.absolutePath)                                   // 绝对路径 (平台原生形态)
                add(sentinel.absolutePath.replace('\\', '/'))                // 正斜杠形态
                if (!isPosix) add("C:/Windows/Temp/harness-should-not-exist.txt")
            }

            for (escape in escapes) {
                val read = runBlocking { tools.invoke(HarnessToolRequest.ofRaw("file.read", escape)) }
                assertFalse(
                    "越界读取必须失败: [$escape] -> ${read.output.take(120)}",
                    read.success
                )
                assertFalse(
                    "不得泄露哨兵内容: [$escape]",
                    read.output.contains("SENTINEL")
                )
            }
            assertEquals("哨兵文件必须原封不动", "绝密内容-SENTINEL", sentinel.readText())

            // 越界写入同样必须失败且不得产生文件
            val evil = java.io.File(outside.toFile(), "created-by-escape.txt")
            for (escape in listOf(evil.absolutePath, evil.absolutePath.replace('\\', '/'))) {
                val write = runBlocking { tools.invoke(HarnessToolRequest.ofRaw("file.write", "$escape x")) }
                assertFalse("越界写入必须失败: [$escape]", write.success)
            }
            assertFalse("越界写入不得创建文件", evil.exists())
        } finally {
            java.io.File(dir).deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `工作目录内的绝对路径与相对路径均可正常读写`() {
        val dir = tempDir()
        try {
            val env = HarnessEnv.jvmDefault(dir)
            val tools = BuiltinTools.registry(env)

            // 相对路径
            assertTrue(
                runBlocking { tools.invoke(HarnessToolRequest.ofRaw("file.write", "rel.txt 相对路径内容")) }.success
            )
            assertTrue(java.io.File("$dir/rel.txt").exists())

            // 绝对路径 (原生分隔符) — Windows 盘符路径曾因被误判为相对路径而失效, 此断言锁死回归
            val absolute = java.io.File(dir, "abs.txt").absolutePath
            val written = runBlocking { tools.invoke(HarnessToolRequest.ofRaw("file.write", "$absolute 绝对路径内容")) }
            assertTrue("baseDir 内的绝对路径应可写: ${written.output}", written.success)
            assertTrue(java.io.File("$dir/abs.txt").exists())

            val read = runBlocking { tools.invoke(HarnessToolRequest.ofRaw("file.read", absolute)) }
            assertTrue("绝对路径应可读回", read.success)
            assertTrue(read.output.contains("绝对路径内容"))
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `无障碍路径段消毒在各平台一致`() {
        val p = com.mengpaw.harness.BaseDirPathResolver("/data/harness")
        // 反斜杠与正斜杠都是路径分隔语义, 必须都被消毒
        assertEquals(".._.._etc_passwd", p.sanitizeSegment("../../etc/passwd"))
        assertEquals(".._.._windows_system32", p.sanitizeSegment("..\\..\\windows\\system32"))
        assertEquals("normal_name", p.sanitizeSegment("normal_name"))
    }
}
