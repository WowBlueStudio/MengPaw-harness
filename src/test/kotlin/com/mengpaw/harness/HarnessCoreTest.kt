// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

import com.mengpaw.harness.jvm.JvmHarnessFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽象层行为测试 — 独立于 MengPaw 主仓库, 验证 harness 核心单靠抽象即可运转。
 *
 * 覆盖重点:
 * ① 路径解析的逻辑布局与消毒规则; ② 文件系统边界的真实读写;
 * ③ 确认门的 fail-closed 安全默认; ④ 工具执行器的契约 (失败不抛异常)。
 */
class HarnessCoreTest {

    // ── 路径解析 ──────────────────────────────────────────────

    @Test
    fun `默认布局各逻辑目录归入 baseDir 之下`() {
        val p = BaseDirPathResolver("/data/harness")
        assertEquals("/data/harness", p.baseDir)
        assertEquals("/data/harness/配置", p.configDir)
        assertEquals("/data/harness/Agent文档", p.agentsDir)
        assertEquals("/data/harness/进化档案", p.evolutionDir)
        assertEquals("/data/harness/输出", p.outputDir)
        assertEquals("/data/harness/mengpaw.sock", p.socketPath)
    }

    @Test
    fun `agentName 中的路径分隔符被消毒 防目录穿越`() {
        val p = BaseDirPathResolver("/data/harness")
        val dir = p.agentDir("../../etc/passwd")
        // 消毒后必须是单一段: 不得出现上跳段 "..", 也不得出现任何分隔符
        val segment = dir.removePrefix("${p.agentsDir}/")
        assertEquals(".._.._etc_passwd", segment)
        assertFalse("不得保留上跳段", segment.split("/").any { it == ".." })
        assertFalse("不得保留原始反斜杠", dir.contains("\\"))
        assertTrue("结果须仍在 agentsDir 之下", dir.startsWith(p.agentsDir))
    }

    @Test
    fun `无主进化档案归全局目录 有主归 agent 工作区`() {
        val p = BaseDirPathResolver("/data/harness")
        assertEquals(p.evolutionDir, p.agentEvolutionDir(null))
        assertEquals(p.evolutionDir, p.agentEvolutionDir(""))
        assertEquals(p.evolutionDir, p.agentEvolutionDir("default"))
        assertEquals("/data/harness/Agent文档/研究员/evolution", p.agentEvolutionDir("研究员"))
    }

    @Test
    fun `ASCII 命名档只改物理目录名 逻辑 API 不受影响`() {
        val p = BaseDirPathResolver("/data/harness", names = BaseDirPathResolver.DirectoryNames.ASCII)
        assertEquals("/data/harness/agents", p.agentsDir)
        assertEquals("/data/harness/Agent文档", BaseDirPathResolver("/data/harness").agentsDir)
        // 记忆目录的末级语义在两种档位下一致
        assertEquals("/data/harness/agents/agent-1/memory", p.memoryDir("agent-1"))
    }

    @Test
    fun `outputDir 与 socketPath 可被宿主覆盖`() {
        val p = BaseDirPathResolver("/data/harness", outputOverride = "/mnt/sd/out", socketOverride = "/run/h.sock")
        assertEquals("/mnt/sd/out", p.outputDir)
        assertEquals("/run/h.sock", p.socketPath)
    }

    // ── 文件系统 ──────────────────────────────────────────────

    @Test
    fun `JVM 文件系统写读追加与列举`() {
        val fs = JvmHarnessFileSystem
        val root = kotlin.io.path.createTempDirectory("harness-fs-test").toString()
        try {
            val file = "$root/note.md"
            assertFalse(fs.exists(file))

            fs.writeText(file, "第一行")
            assertTrue(fs.exists(file))
            assertEquals("第一行", fs.readText(file))

            fs.appendText(file, "\n第二行")
            assertEquals("第一行\n第二行", fs.readText(file))
            assertTrue(fs.size(file) > 0)

            fs.mkdirs("$root/arch")
            fs.writeText("$root/arch/a_memory.md", "x")
            fs.writeText("$root/arch/b_memory.md", "y")
            fs.writeText("$root/arch/other.txt", "z")
            assertEquals(3, fs.list("$root/arch").size)
            assertEquals(
                listOf("a_memory.md", "b_memory.md"),
                fs.listFiltered("$root/arch", suffix = "_memory.md").sorted()
            )

            assertTrue(fs.delete(file))
            assertFalse(fs.exists(file))
            assertEquals(-1L, fs.size(file))
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `写入时按需创建父目录`() {
        val fs = JvmHarnessFileSystem
        val root = kotlin.io.path.createTempDirectory("harness-mkdir-test").toString()
        try {
            val deep = "$root/a/b/c/note.md"
            fs.writeText(deep, "深层内容")
            assertEquals("深层内容", fs.readText(deep))
            assertTrue(fs.isDirectory("$root/a/b/c"))
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `目录不存在时列举返回空表 不抛异常`() {
        val fs = JvmHarnessFileSystem
        assertEquals(emptyList<String>(), fs.list("/definitely/not/exist/xyz"))
        assertFalse(fs.isDirectory("/definitely/not/exist/xyz"))
        assertEquals(0L, fs.lastModified("/definitely/not/exist/xyz"))
    }

    // ── 确认门 (安全默认) ────────────────────────────────────

    @Test
    fun `默认确认门一律拒绝 且标记为无人可问`() = runBlocking {
        val decision = DenyAllConfirmGate.request("proc.exec", "用户要求", "高危", 1000L)
        assertEquals(ConfirmDecision.NO_LISTENER, decision)
        assertFalse("安全默认必须不放行", decision.isAllowed)
    }

    @Test
    fun `ALLOWED 是唯一放行态`() {
        assertTrue(ConfirmDecision.ALLOWED.isAllowed)
        assertFalse(ConfirmDecision.DENIED.isAllowed)
        assertFalse(ConfirmDecision.NO_LISTENER.isAllowed)
        assertFalse(ConfirmDecision.TIMEOUT.isAllowed)
    }

    // ── 工具执行器契约 ───────────────────────────────────────

    @Test
    fun `工具执行失败以结果表达 不抛异常`() = runBlocking {
        val invoker = object : HarnessToolInvoker {
            override suspend fun invoke(request: HarnessToolRequest): HarnessToolResult =
                HarnessToolResult.fail("命令不存在: ${request.name}", errorCode = "ERR_NOT_FOUND")
        }
        val result = invoker.invoke(HarnessToolRequest.ofRaw("fs.cat", "/tmp/x"))
        assertFalse(result.success)
        assertEquals("ERR_NOT_FOUND", result.errorCode)
        assertEquals(emptyList<HarnessToolSpec>(), invoker.listTools())
    }

    @Test
    fun `raw 参数经保留键透传 空参数不产生键`() {
        val withRaw = HarnessToolRequest.ofRaw("fs.cat", "/tmp/x y")
        assertEquals("/tmp/x y", withRaw.raw)

        val noRaw = HarnessToolRequest.ofRaw("agent.cli", "")
        assertEquals(emptyMap<String, String>(), noRaw.flags)
        assertEquals(null, noRaw.raw)
    }

    // ── 平台环境聚合 ─────────────────────────────────────────

    @Test
    fun `jvmDefault 组装出可直接使用的环境`() {
        val env = HarnessEnv.jvmDefault("/data/harness")
        assertEquals("/data/harness", env.paths.baseDir)
        assertEquals("/data/harness/配置", env.paths.configDir)
        assertEquals(JvmHarnessFileSystem, env.fileSystem)
        assertEquals(DenyAllConfirmGate, env.confirmGate)
        assertTrue("默认时钟须给出毫秒时间戳", env.clock.nowMillis() > 0L)
        assertEquals(10, env.clock.today().length)
    }
}
