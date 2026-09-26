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
 * ③ 损坏文件 fail-soft; ④ [HarnessEnv] 默认注入。
 */
class CheckpointStoreTest {

    private fun sample(
        sessionId: String = "s1",
        status: CheckpointStatus = CheckpointStatus.RUNNING
    ) = Checkpoint(
        sessionId = sessionId,
        task = "统计 ./src 下有多少个 Kotlin 文件",
        step = 3,
        status = status,
        messages = listOf(
            CheckpointMessage("user", "统计 ./src 下有多少个 Kotlin 文件"),
            CheckpointMessage("assistant", "Thought: 先列目录\nAction: file.ls\nAction Input: src"),
            CheckpointMessage("system", "Observation (file.ls):\n\"a.kt\", \"b.kt\"\n含引号与换行")
        ),
        updatedAt = 1_700_000_000_000L
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
}
