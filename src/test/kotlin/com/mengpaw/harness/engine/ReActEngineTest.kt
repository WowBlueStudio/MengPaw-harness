// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.engine

import com.mengpaw.harness.HarnessEnv
import com.mengpaw.harness.HarnessToolInvoker
import com.mengpaw.harness.HarnessToolRequest
import com.mengpaw.harness.HarnessToolResult
import com.mengpaw.harness.tool.BuiltinTools
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReAct 循环端到端测试 — 验证"库能被真正跑起来"。
 *
 * 覆盖: 完整 思考→行动→观察→答复 闭环、工具真执行、未知工具、只思考不行动、
 * 步数上限、空响应重试、循环检测、并行多动作、以及内置工具本身的行为。
 * 全部用假模型 + 真实文件系统 (临时目录), 不触网。
 */
class ReActEngineTest {

    /** 脚本化模型 — 按序返回预设输出, 用尽后重复最后一条。 */
    private class ScriptedLlm(private val script: List<String>) : LlmProvider {
        var calls = 0
            private set
        private val seenMessages = mutableListOf<List<Map<String, String>>>()

        override suspend fun complete(prompt: String): String = next()

        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
            seenMessages.add(messages)
            return next()
        }

        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            next().also { onToken(it) }

        override suspend fun completeStreamingWithMessages(
            messages: List<Map<String, String>>,
            onToken: (String) -> Unit,
            onReasoning: ((String) -> Unit)?
        ): String {
            seenMessages.add(messages)
            return next().also { onToken(it) }
        }

        override fun info(): ProviderInfo = ProviderInfo("scripted", "mock", ProviderType.LOCAL)
        override fun close() {}

        fun messagesAt(index: Int): List<Map<String, String>>? = seenMessages.getOrNull(index)

        private fun next(): String {
            val r = script[minOf(calls, script.size - 1)]
            calls++
            return r
        }
    }

    private class RecordingInvoker(
        private val delegate: HarnessToolInvoker
    ) : HarnessToolInvoker {
        val invoked = mutableListOf<String>()
        override suspend fun invoke(request: HarnessToolRequest): HarnessToolResult {
            invoked.add(request.name)
            return delegate.invoke(request)
        }
        override fun listTools() = delegate.listTools()
    }

    private fun newEnv(): Pair<HarnessEnv, String> {
        val dir = java.nio.file.Files.createTempDirectory("harness-react-test").toString()
        return HarnessEnv.jvmDefault(dir) to dir
    }

    // ── 核心闭环 ────────────────────────────────────────────────

    @Test
    fun `完整闭环 思考 行动 观察 最终答复`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            // 第一步让模型写文件, 第二步它读到内容后给最终答复
            val llm = ScriptedLlm(listOf(
                """
                Thought: 先把内容写入文件。
                Action: file.write
                Action Input: notes/demo.md 你好 MengPaw
                """.trimIndent(),
                """
                Thought: 确认写入结果。
                Action: file.read
                Action Input: notes/demo.md
                """.trimIndent(),
                "Final Answer: 文件已写入并读回成功。"
            ))
            val invoker = RecordingInvoker(BuiltinTools.registry(env))
            val engine = ReActEngine(llm, invoker, maxSteps = 5)

            val events = mutableListOf<StepEvent>()
            val result = engine.run("把问候写入 notes/demo.md 并确认", onStep = { events.add(it) })

            assertTrue("应正常完成: ${result.answer}", result.completed)
            assertEquals("文件已写入并读回成功。", result.answer)
            assertEquals("应执行 3 轮", 3, result.steps)
            assertEquals(listOf("file.write", "file.read"), invoker.invoked)
            assertTrue("文件应真实写盘", java.io.File("$dir/notes/demo.md").readText().contains("你好 MengPaw"))
            // 观察事件应包含模型写出的内容
            val observations = events.filterIsInstance<StepEvent.Observation>()
            assertEquals(2, observations.size)
            assertTrue(observations.all { it.success })
            assertTrue("第二次观察应含文件内容", observations[1].output.contains("你好 MengPaw"))
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `系统提示词自动注入可用工具清单`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            val llm = ScriptedLlm(listOf("Final Answer: 好的。"))
            val engine = ReActEngine(llm, BuiltinTools.registry(env))
            engine.run("测试")

            val first = llm.messagesAt(0)!!
            val system = first.first { it["role"] == "system" }["content"]!!
            assertTrue("应列出 file.read", system.contains("file.read"))
            assertTrue("应列出 file.write", system.contains("file.write"))
            assertTrue("应教会输出格式", system.contains("Final Answer:"))
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `无工具时提示词明确告知模型不要臆造动作`() {
        val prompt = PromptBuilder.buildSystemPrompt(toolInvoker = null)
        assertTrue(prompt.contains("没有任何可用工具"))
    }

    // ── 异常与边界 ───────────────────────────────────────────────

    @Test
    fun `未知工具返回可读错误且不中断循环`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            val llm = ScriptedLlm(listOf(
                """
                Thought: 试个不存在的工具。
                Action: does.not.exist
                Action Input: x
                """.trimIndent(),
                "Final Answer: 工具不存在, 我改用其它方式。"
            ))
            val invoker = BuiltinTools.registry(env)
            val engine = ReActEngine(llm, invoker, maxSteps = 4)
            val events = mutableListOf<StepEvent>()
            val result = engine.run("测试未知工具", onStep = { events.add(it) })

            assertTrue("未知工具不应中断循环", result.completed)
            val obs = events.filterIsInstance<StepEvent.Observation>().first()
            assertFalse(obs.success)
            assertTrue("错误应说明可用工具", obs.output.contains("未知工具"))
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `只思考不行动两次后终止`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            val llm = ScriptedLlm(listOf("Thought: 我再想想, 但一直不调用工具。"))
            val engine = ReActEngine(llm, BuiltinTools.registry(env), maxSteps = 6)
            val result = engine.run("陷入空想")
            assertFalse(result.completed)
            assertEquals("no_action", result.terminationReason)
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `达到步数上限时终止并说明原因`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            // 模型永远重复同一个读取动作
            val llm = ScriptedLlm(listOf(
                """
                Thought: 读文件。
                Action: file.read
                Action Input: missing.txt
                """.trimIndent()
            ))
            val engine = ReActEngine(llm, BuiltinTools.registry(env), maxSteps = 2)
            val result = engine.run("无限读取")
            assertFalse(result.completed)
            assertTrue(
                "应为循环检测或步数上限: ${result.terminationReason}",
                result.terminationReason in listOf("loop_detected", "max_steps")
            )
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `空响应重试一次后失败即终止`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            val llm = ScriptedLlm(listOf("", "", ""))
            val engine = ReActEngine(llm, BuiltinTools.registry(env), maxSteps = 5)
            val result = engine.run("触发空响应")
            assertFalse(result.completed)
            assertEquals("empty_response", result.terminationReason)
            assertTrue("应提示模型端点异常", result.answer.contains("空响应"))
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `一轮多个动作会被并行执行`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            java.io.File(dir, "a.txt").writeText("AAA")
            java.io.File(dir, "b.txt").writeText("BBB")
            val llm = ScriptedLlm(listOf(
                """
                Thought: 同时读两个文件。
                Action: file.read
                Action Input: a.txt
                Action: file.read
                Action Input: b.txt
                """.trimIndent(),
                "Final Answer: 两个文件都读到了。"
            ))
            val invoker = RecordingInvoker(BuiltinTools.registry(env))
            val engine = ReActEngine(llm, invoker, maxSteps = 4)
            val events = mutableListOf<StepEvent>()
            val result = engine.run("并行读取", onStep = { events.add(it) })

            assertTrue(result.completed)
            assertEquals("两个动作都应执行", 2, invoker.invoked.size)
            assertEquals(2, events.filterIsInstance<StepEvent.Action>().size)
            assertEquals(2, events.filterIsInstance<StepEvent.Observation>().size)
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `会话历史在轮次间累积并可供模型看到观察`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            java.io.File(dir, "data.txt").writeText("机密内容42")
            val llm = ScriptedLlm(listOf(
                """
                Thought: 读取数据。
                Action: file.read
                Action Input: data.txt
                """.trimIndent(),
                "Final Answer: 读到了 机密内容42。"
            ))
            val engine = ReActEngine(llm, BuiltinTools.registry(env), maxSteps = 4)
            val result = engine.run("读 data.txt")
            assertTrue(result.completed)

            // 第二次调用模型时, 历史里应已包含上一次的 Observation
            val secondCall = llm.messagesAt(1)!!
            val joined = secondCall.joinToString("\n") { it["content"].orEmpty() }
            assertTrue("模型应看到工具观察: $joined", joined.contains("机密内容42"))
            assertTrue("观察应带标签", joined.contains("Observation"))
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `新会话清空历史`() = runBlocking {
        val (env, dir) = newEnv()
        try {
            val llm = ScriptedLlm(listOf("Final Answer: 完成。"))
            val engine = ReActEngine(llm, BuiltinTools.registry(env))
            engine.run("第一轮")
            assertEquals(2, engine.history().size)
            engine.newConversation()
            assertEquals(0, engine.history().size)
        } finally {
            java.io.File(dir).deleteRecursively()
        }
    }
}
