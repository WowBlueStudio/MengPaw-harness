// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

import com.mengpaw.harness.jvm.JvmHarnessFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查点存储测试 — 覆盖内存/文件两种实现的关键契约。
 *
 * 重点: ① 跨实例持久 (断点续跑的价值所在); ② 会话 id 消毒 (路径穿越防线);
 * ③ 损坏文件 fail-soft; ④ [HarnessEnv] 默认注入;
 * ⑤ 保留策略 (keep) 与 listSessionIds 的**精确解析** (不再靠 removeSuffix 猜)。
 */
class CheckpointStoreTest {

    private fun sample(
        sessionId: String = "s1",
        status: CheckpointStatus = CheckpointStatus.RUNNING,
        step: Int = 3,
        updatedAt: Long = 1_700_000_000_000L
    ) = Checkpoint(
        sessionId = sessionId,
        task = "统计 ./src 下有多少个 Kotlin 文件",
        step = step,
        status = status,
        messages = listOf(
            CheckpointMessage("user", "统计 ./src 下有多少个 Kotlin 文件"),
            CheckpointMessage("assistant", "Thought: 先列目录\nAction: file.ls\nAction Input: src"),
            CheckpointMessage("system", "Observation (file.ls):\n\"a.kt\", \"b.kt\"\n含引号与换行")
        ),
        updatedAt = updatedAt
    )

    private fun tempDir(prefix: String): String =
        java.nio.file.Files.createTempDirectory(prefix).toString()

    @Test
    fun `内存存储往返与清理`() = runBlocking {
        val store = InMemoryCheckpointStore()
        assertNull("未写入时应为 null", store.load("s1"))

        store.save(sample())
        val loaded = store.load("s1")
        assertTrue("写入后应读回", loaded != null)
        assertEquals(3, loaded!!.step)
        assertEquals(CheckpointStatus.RUNNING, loaded.status)
        assertEquals(3, loaded.messages.size)
        assertEquals(listOf("s1"), store.listSessionIds())

        store.clear("s1")
        assertNull("清理后应为 null", store.load("s1"))
        assertTrue(store.listSessionIds().isEmpty())
    }

    @Test
    fun `文件存储跨实例可续跑且中文换行不损坏`() = runBlocking {
        val root = tempDir("harness-ckpt")
        try {
            val dir = "$root/会话检查点"
            FileCheckpointStore(JvmHarnessFileSystem, dir).save(sample())

            // 新实例读同一目录 — 等价于进程重启后恢复
            val afterRestart = FileCheckpointStore(JvmHarnessFileSystem, dir)
            val loaded = afterRestart.load("s1")
            assertTrue("重启后应能读到检查点", loaded != null)
            assertEquals(CheckpointStatus.RUNNING, loaded!!.status)
            assertEquals("统计 ./src 下有多少个 Kotlin 文件", loaded.task)
            assertEquals(3, loaded.step)
            assertTrue(
                "中文与换行必须原样保留",
                loaded.messages[2].content.contains("含引号与换行")
            )
            assertEquals(listOf("s1"), afterRestart.listSessionIds())

            afterRestart.clear("s1")
            assertNull(afterRestart.load("s1"))
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `会话 id 消毒后不逃出检查点目录`() = runBlocking {
        val root = tempDir("harness-ckpt-escape")
        try {
            val dir = "$root/检查点"
            val store = FileCheckpointStore(JvmHarnessFileSystem, dir)
            val evil = "../../evil"

            val path = store.pathFor(evil)
            assertTrue("路径必须落在检查点目录内: $path", path.startsWith("$dir/"))
            assertFalse("消毒后不得保留 ..: $path", path.contains(".."))

            store.save(sample(sessionId = evil))
            assertEquals(
                "只应在检查点目录内落一个文件",
                1,
                JvmHarnessFileSystem.listFiltered(dir, suffix = ".json").size
            )
            assertFalse("不得在目录外生成文件", java.io.File(root, "evil.json").exists())
            assertEquals(3, store.load(evil)!!.step)
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `损坏的检查点文件视为无检查点`() = runBlocking {
        val root = tempDir("harness-ckpt-broken")
        try {
            val dir = "$root/检查点"
            JvmHarnessFileSystem.writeText("$dir/broken.json", "{ 这不是合法 JSON", createParentDirs = true)
            val store = FileCheckpointStore(JvmHarnessFileSystem, dir)
            assertNull("损坏档应按无检查点处理, 不抛异常", store.load("broken"))
            assertEquals("文件仍应可枚举, 便于宿主巡检", listOf("broken"), store.listSessionIds())
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `HarnessEnv 默认注入内存检查点存储`() {
        val root = tempDir("harness-ckpt-env")
        try {
            val env = HarnessEnv.jvmDefault(root)
            assertTrue(
                "默认应为内存实现 (要持久化须显式传 FileCheckpointStore)",
                env.checkpoints is InMemoryCheckpointStore
            )
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `会话 id 消毒规则`() {
        assertEquals("s1", sanitizeCheckpointId("s1"))
        val escaped = sanitizeCheckpointId("../../etc/passwd")
        assertFalse("不得保留 ..: $escaped", escaped.contains(".."))
        assertFalse("不得保留路径分隔符: $escaped", escaped.contains("/"))
        assertTrue("应保留可读主体: $escaped", escaped.endsWith("etc_passwd"))
        assertEquals("session", sanitizeCheckpointId("   "))
        assertEquals(CHECKPOINT_ID_MAX_LEN, sanitizeCheckpointId("x".repeat(200)).length)
    }

    @Test
    fun `保留策略只留最近 N 份且永不删刚写入的那份`() = runBlocking {
        val root = tempDir("harness-ckpt-retain")
        try {
            val dir = "$root/检查点"
            val store = FileCheckpointStore(JvmHarnessFileSystem, dir, keep = 2)

            (1..4).forEach { step ->
                // updatedAt 递增, 使排序判据与步数一致 (避免依赖同毫秒写入的顺序)
                store.save(sample(step = step, updatedAt = 1_700_000_000_000L + step))
                assertEquals(
                    "第 $step 步写入后必须立即可读",
                    "统计 ./src 下有多少个 Kotlin 文件",
                    store.load("s1")?.task
                )
            }

            val files = JvmHarnessFileSystem.listFiltered(dir, suffix = ".json")
            assertEquals("应只保留最近 2 份, 实际: $files", 2, files.size)
            assertTrue("最新一份必须留下: $files", files.contains("s1__step_4.json"))
            assertTrue("次新一份必须留下: $files", files.contains("s1__step_3.json"))
            assertFalse("更旧的应被清理: $files", files.contains("s1__step_1.json"))
            // 档内 updatedAt 新者胜 (不靠文件名假设)
            assertEquals(4, store.load("s1")?.step)
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `保留策略默认不清理`() = runBlocking {
        val root = tempDir("harness-ckpt-nokeep")
        try {
            val dir = "$root/检查点"
            val store = FileCheckpointStore(JvmHarnessFileSystem, dir)

            (1..4).forEach { step -> store.save(sample(step = step, updatedAt = 1_700_000_000_000L + step)) }

            val files = JvmHarnessFileSystem.listFiltered(dir, suffix = ".json")
            assertEquals("默认 keep=0 = 不清理 (与引入保留策略前行为一致)", 4, files.size)
            assertEquals(4, store.load("s1")?.step)
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `会话 id 精确解析且前缀不互相污染`() = runBlocking {
        val root = tempDir("harness-ckpt-prefix")
        try {
            val dir = "$root/检查点"
            val store = FileCheckpointStore(JvmHarnessFileSystem, dir)

            store.save(sample(sessionId = "a", step = 1))
            store.save(sample(sessionId = "ab", step = 2))

            // 修复前 listSessionIds 用 removeSuffix 猜, load 靠单档 — 前缀会话会互相污染
            assertEquals(listOf("a", "ab"), store.listSessionIds())
            assertEquals("a 必须读回自己的档", "a", store.load("a")?.sessionId)
            assertEquals(1, store.load("a")?.step)
            assertEquals("ab 必须读回自己的档", "ab", store.load("ab")?.sessionId)
            assertEquals(2, store.load("ab")?.step)
            assertNull("不存在的会话读不到", store.load("abc"))

            // 同一会话多份步数档归并为一个 id (而不是每个档一个会话)
            store.save(sample(sessionId = "a", step = 2))
            assertEquals(listOf("a", "ab"), store.listSessionIds())
            assertEquals("多档时取 updatedAt 最新", 2, store.load("a")?.step)

            // 目录里混入任意 *.json (非本类所写) 不得被当成会话; 以 .json 结尾的会话 id 也不歧义
            JvmHarnessFileSystem.writeText("$dir/notes.json", "{}", createParentDirs = true)
            JvmHarnessFileSystem.writeText("$dir/readme.txt", "x", createParentDirs = true)
            store.save(sample(sessionId = "report.json", step = 3))
            assertEquals(listOf("a", "ab", "notes", "report_json"), store.listSessionIds())
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }

    @Test
    fun `旧格式单档仍可读且不干扰枚举`() = runBlocking {
        val root = tempDir("harness-ckpt-legacy")
        try {
            val dir = "$root/检查点"
            val store = FileCheckpointStore(JvmHarnessFileSystem, dir)

            // 早期实现: 每会话单档 {dir}/{id}.json
            store.save(sample(sessionId = "old", step = 5, updatedAt = 1_700_000_000_005L))
            JvmHarnessFileSystem.writeText(
                path = store.pathFor("old"),
                content = JvmHarnessFileSystem.readText(store.pathFor("old", 5)),
                createParentDirs = true
            )
            JvmHarnessFileSystem.delete(store.pathFor("old", 5))

            val legacy = store.load("old")
            assertTrue("旧格式单档必须仍可读", legacy != null)
            assertEquals(5, legacy?.step)
            assertEquals("旧档与步数档归并为同一会话", listOf("old"), store.listSessionIds())
        } finally {
            java.io.File(root).deleteRecursively()
        }
    }
}
