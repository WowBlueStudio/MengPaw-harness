// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.examples

import com.mengpaw.harness.HarnessEnv
import com.mengpaw.harness.tool.BuiltinTools
import com.mengpaw.harness.tool.HarnessTool
import com.mengpaw.harness.tool.ToolInput
import com.mengpaw.harness.tool.ToolOutcome
import com.mengpaw.harness.tool.ToolRegistry
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速上手示例 — 同时是可执行测试, 保证文档里的代码不会腐烂。
 *
 * 三个层次:
 * 1. [最小闭环]: 6 行搭起一个能跑的工具调用循环 (用自己的模型/桩)
 * 2. [自定义工具]: 注册自己的工具参与循环
 * 3. [接入真实模型]: AdaptiveLlmProvider 接 OpenAI 兼容端点 (需 API Key, 故仅示范构造)
 *
 * 说明: 示例用"桩模型"而非真实 API, 这样示例本身可在 CI 离线跑通;
 * 换成真实模型只需替换 [LlmProvider] 实例 (见 [接入真实模型的构造方式])。
 */
class QuickStartExample {

    /** 桩模型 — 演示用; 真实场景请用 AdaptiveLlmProvider (见本文件末尾)。 */
    private class DemoLlm(private val replies: List<String>) : LlmProvider {
        private var i = 0
        override suspend fun complete(prompt: String): String = replies[minOf(i++, replies.size - 1)]
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
            replies[minOf(i++, replies.size - 1)]
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            complete(prompt).also { onToken(it) }
        override fun info() = ProviderInfo("demo", "demo-model", ProviderType.LOCAL)
        override fun close() {}
    }

    /** 场景 1: 最小闭环 — 环境 → 工具 → 模型 → 引擎。 */
    @Test
    fun `最小闭环`() = runBlocking {
        val workDir = java.nio.file.Files.createTempDirectory("harness-quickstart").toString()
        try {
            // ① 平台环境: 数据目录 + 默认文件系统/时钟/日志
            val env = HarnessEnv.jvmDefault(baseDir = workDir)

            // ② 工具: 内置 6 个基础工具 (file.read/write/ls/glob, env.get, net.get)
            val tools = BuiltinTools.registry(env)

            // ③ 模型: 这里用桩; 生产环境换成 AdaptiveLlmProvider(...)
            val llm = DemoLlm(listOf(
                "Thought: 先写文件。\nAction: file.write\nAction Input: hello.txt 你好 Harness",
                "Final Answer: 已写入 hello.txt。"
            ))

            // ④ 引擎: 自动用工具清单生成系统提示词
            val engine = com.mengpaw.harness.engine.ReActEngine(
                llmProvider = llm,
                toolInvoker = tools,
                maxSteps = 5
            )

            // ⑤ 运行
            val result = engine.run("把问候写进 hello.txt")

            assertTrue("应正常完成", result.completed)
            assertEquals("已写入 hello.txt。", result.answer)
            assertTrue(
                "文件应真实落盘",
                java.io.File(workDir, "hello.txt").readText().contains("你好 Harness")
            )
        } finally {
            java.io.File(workDir).deleteRecursively()
        }
    }

    /** 场景 2: 注册自定义工具 — 任何 Kotlin 逻辑都能成为 Agent 的能力。 */
    @Test
    fun `自定义工具参与循环`() = runBlocking {
        val workDir = java.nio.file.Files.createTempDirectory("harness-custom-tool").toString()
        try {
            val env = HarnessEnv.jvmDefault(baseDir = workDir)

            // 自定义工具: 一个简单的四则运算器
            val calculator = object : HarnessTool {
                override val name = "calc.eval"
                override val description = "计算两个整数的四则运算"
                override val parameters = "<a> <op> <b>  例: calc.eval 3 * 4"
                override val riskLevel = "low"

                override suspend fun execute(input: ToolInput): ToolOutcome {
                    val a = input.arg(0)?.toIntOrNull()
                        ?: return ToolOutcome.fail("第一个操作数不是整数")
                    val op = input.arg(1) ?: return ToolOutcome.fail("缺少运算符")
                    val b = input.arg(2)?.toIntOrNull()
                        ?: return ToolOutcome.fail("第二个操作数不是整数")
                    val r = when (op) {
                        "+" -> a + b
                        "-" -> a - b
                        "*" -> a * b
                        "/" -> if (b == 0) return ToolOutcome.fail("除数不能为 0") else a / b
                        else -> return ToolOutcome.fail("不支持的运算符: $op")
                    }
                    return ToolOutcome.ok("$a $op $b = $r")
                }
            }

            val tools = ToolRegistry(env).register(calculator)
            val llm = DemoLlm(listOf(
                "Thought: 需要计算。\nAction: calc.eval\nAction Input: 21 * 2",
                "Final Answer: 计算结果是 42。"
            ))
            val engine = com.mengpaw.harness.engine.ReActEngine(llm, tools, maxSteps = 4)

            val events = mutableListOf<com.mengpaw.harness.engine.StepEvent>()
            val result = engine.run("算一下 21 乘 2", onStep = { events.add(it) })

            assertTrue(result.completed)
            val obs = events
                .filterIsInstance<com.mengpaw.harness.engine.StepEvent.Observation>()
                .single()
            assertTrue("自定义工具应被执行并返回结果: ${obs.output}", obs.output.contains("42"))
        } finally {
            java.io.File(workDir).deleteRecursively()
        }
    }

    /**
     * 场景 3: 接入真实模型 (仅示范构造, 不实际请求 — 需要 API Key)。
     *
     * 真实用法:
     * ```
     * val llm = AdaptiveLlmProvider(
     *     apiKey = System.getenv("DEEPSEEK_API_KEY"),
     *     apiEndpoint = "https://api.deepseek.com/v1/chat/completions",
     *     model = "deepseek-chat"
     * )
     * ```
     * 任何 OpenAI 兼容端点 (OpenAI / DeepSeek / Moonshot / 本地 Ollama …) 都适用。
     * 密钥从环境变量读取, **绝不写死在代码或提交进仓库**。
     */
    @Test
    fun `接入真实模型的构造方式`() {
        // 只断言类可被引用与构造签名存在, 不发起网络请求
        assertTrue(
            "AdaptiveLlmProvider 应可被构造",
            com.mengpaw.kernel.llm.AdaptiveLlmProvider::class.java.declaredConstructors.isNotEmpty()
        )
    }
}
