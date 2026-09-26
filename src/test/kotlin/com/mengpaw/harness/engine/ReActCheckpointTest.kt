// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.engine

import com.mengpaw.harness.Checkpoint
import com.mengpaw.harness.CheckpointMessage
import com.mengpaw.harness.CheckpointStatus
import com.mengpaw.harness.CheckpointStore
import com.mengpaw.harness.HarnessToolInvoker
import com.mengpaw.harness.HarnessToolRequest
import com.mengpaw.harness.HarnessToolResult
import com.mengpaw.harness.InMemoryCheckpointStore
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续跑端到端测试 — 检查点与 ReAct 循环的接线。
 *
 * 覆盖: 终态落盘、RUNNING 续跑 (历史恢复 + 步数接续 + 不重复任务)、终态不可续、
 * 未配置存储时行为不变、存储异常不中断主任务。全部用假模型, 不触网。
 */
class ReActCheckpointTest {

    private class ScriptedLlm(private val script: List<String>) : LlmProvider {
        private val seenMessages = mutableListOf<List<Map<String, String>>>()
        private var calls = 0

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

    /** 不会被调用的工具执行器 — 本轮脚本只产出 Final Answer。 */
    private fun unusedInvoker() = object : HarnessToolInvoker {
        override suspend fun invoke(request: HarnessToolRequest): HarnessToolResult =
            HarnessToolResult.ok("(不应被调用: ${request.name})")
    }

    @Test
    fun `运行完成后写入 COMPLETED 检查点`() = runBlocking {
        val store = InMemoryCheckpointStore()
        val llm = ScriptedLlm(listOf("Final Answer: 完成。"))
        val engine = ReActEngine(
            llm, unusedInvoker(), maxSteps = 3,
            checkpointStore = store, sessionId = "t1"
        )

        val result = engine.run("任务A")

        assertTrue("应正常完成: ${result.answer}", result.completed)
        val cp = store.load("t1")
        assertTrue("终态检查点应落盘", cp != null)
        assertEquals(CheckpointStatus.COMPLETED, cp!!.status)
        assertEquals("完成。", cp.answer)
        assertEquals("任务A", cp.task)
        assertTrue(
            "消息序列应含用户任务",
            cp.messages.any { it.role == "user" && it.content == "任务A" }
        )
    }

    @Test
    fun `RUNNING 检查点续跑时恢复历史且不重复任务`() = runBlocking {
        val store = InMemoryCheckpointStore()
        val task = "统计 src 下 Kotlin 文件数"
        store.save(
            Checkpoint(
                sessionId = "t2",
                task = task,
                step = 5,
                status = CheckpointStatus.RUNNING,
                messages = listOf(
                    CheckpointMessage("user", task),
                    CheckpointMessage("system", "Observation (file.ls): 共 3 个文件")
                ),
                updatedAt = 1L
            )
        )
        val llm = ScriptedLlm(listOf("Final Answer: 接着上次的结果, 共 3 个。"))
        val engine = ReActEngine(
            llm, unusedInvoker(), maxSteps = 10,
            checkpointStore = store, sessionId = "t2"
        )

        val result = engine.run(task, resume = true)

        assertTrue(result.completed)
        assertEquals("步数应从检查点的 5 接续 (本轮为第 6 步)", 6, result.steps)

        val firstCall = llm.messagesAt(0)
        assertTrue("应发生一次模型调用", firstCall != null)
        assertEquals(
            "恢复后不得重复追加任务消息",
            1,
            firstCall!!.count { it["role"] == "user" }
        )
        assertTrue(
            "恢复的历史应被模型看到",
            firstCall.any { it["content"]?.contains("共 3 个文件") == true }
        )
    }

    @Test
    fun `终态检查点不续跑 按新任务重新开始`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.save(
            Checkpoint(
                sessionId = "t3",
                task = "旧任务",
                step = 9,
                status = CheckpointStatus.COMPLETED,
                messages = listOf(CheckpointMessage("user", "旧任务")),
                updatedAt = 1L,
                answer = "旧答复"
            )
        )
        val llm = ScriptedLlm(listOf("Final Answer: 新任务完成。"))
        val engine = ReActEngine(
            llm, unusedInvoker(), maxSteps = 5,
            checkpointStore = store, sessionId = "t3"
        )

        val result = engine.run("新任务", resume = true)

        assertTrue(result.completed)
        assertEquals("终态不可续, 步数从 0 起算", 1, result.steps)
        val firstCall = llm.messagesAt(0)
        assertTrue(firstCall!!.any { it["content"]?.contains("新任务") == true })
        assertTrue(
            "不应把旧会话历史带进来",
            firstCall.none { it["content"]?.contains("旧任务") == true }
        )
    }

    @Test
    fun `未配置存储时不写检查点且行为不变`() = runBlocking {
        val llm = ScriptedLlm(listOf("Final Answer: 完成。"))
        val engine = ReActEngine(llm, unusedInvoker(), maxSteps = 3)

        val result = engine.run("无检查点任务")

        assertTrue(result.completed)
        assertNull("未注入存储应返回 null", engine.checkpoint())
    }

    @Test
    fun `检查点写入失败不中断主任务且经回调上报`() = runBlocking {
        val failing = object : CheckpointStore {
            override suspend fun load(sessionId: String): Checkpoint? = null
            override suspend fun save(checkpoint: Checkpoint) {
                throw java.io.IOException("磁盘已满")
            }
            override suspend fun clear(sessionId: String) = Unit
        }
        var errors = 0
        val llm = ScriptedLlm(listOf("Final Answer: 完成。"))
        val engine = ReActEngine(
            llm, unusedInvoker(), maxSteps = 3,
            checkpointStore = failing, sessionId = "t5",
            onCheckpointError = { errors++ }
        )

        val result = engine.run("落盘失败任务")

        assertTrue("检查点失败不得中断任务", result.completed)
        assertTrue("异常必须可见 (不得静默吞掉)", errors >= 1)
    }

    @Test
    fun `clearCheckpoint 清理本会话检查点`() = runBlocking {
        val store = InMemoryCheckpointStore()
        val llm = ScriptedLlm(listOf("Final Answer: 完成。"))
        val engine = ReActEngine(
            llm, unusedInvoker(), maxSteps = 3,
            checkpointStore = store, sessionId = "t6"
        )

        engine.run("任务")
        assertTrue(engine.checkpoint() != null)
        engine.clearCheckpoint()
        assertNull(engine.checkpoint())
    }
}
