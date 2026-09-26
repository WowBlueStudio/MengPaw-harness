// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 内存检查点存储 — 默认实现 (进程内, 不落盘)。
 *
 * 适用: 同进程内的"中断后接着跑"、"UI 重进恢复"、测试隔离。
 * **不适用**: 进程重启 / 崩溃恢复 — 需持久化请用 [FileCheckpointStore]。
 *
 * 用 [Mutex] 而非并发容器, 是为守住"核心零平台类型"铁律 (`java.util.concurrent` 属平台类型,
 * 见门禁 `verifyNoPlatformTypes`); 检查点写入频率是"每步一次", 锁竞争可以忽略。
 */
class InMemoryCheckpointStore : CheckpointStore {

    private val mutex = Mutex()
    private val store = mutableMapOf<String, Checkpoint>()

    override suspend fun load(sessionId: String): Checkpoint? = mutex.withLock { store[sessionId] }

    override suspend fun save(checkpoint: Checkpoint) {
        mutex.withLock { store[checkpoint.sessionId] = checkpoint }
    }

    override suspend fun clear(sessionId: String) {
        mutex.withLock { store.remove(sessionId) }
    }

    override suspend fun listSessionIds(): List<String> = mutex.withLock { store.keys.sorted() }
}
