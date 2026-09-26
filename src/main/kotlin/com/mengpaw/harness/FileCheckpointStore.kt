// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

import kotlinx.serialization.json.Json

/**
 * 文件检查点存储 — 每会话一个 JSON 文件 (`{dir}/{sessionId}.json`)。
 *
 * 这是**跨进程断点续跑**的参考实现: 落盘一律经 [HarnessFileSystem] (唯一边界),
 * 因此零平台类型, 任意宿主 (Android / 桌面 / 未来 KMP 目标) 都能直接用。
 * 典型接法: `FileCheckpointStore(env.fileSystem, env.paths.checkpointDir)`。
 *
 * 安全: 会话 id 拼路径前先经 [sanitizeCheckpointId] 消毒 (点号被替换, `..` 无法存活),
 * 拼出的路径永远在 [dir] 内。**不要把未经本类处理的 id 拿去自行拼路径。**
 *
 * 读侧 fail-soft: 文件不存在或内容损坏时 [load] 返回 null (视为无检查点), 由引擎决定
 * 是否新开会话 — 检查点损坏不应让任务直接失败。
 *
 * @param fileSystem 文件系统抽象 (由宿主注入)
 * @param dir 检查点目录 (通常取 `HarnessPathResolver.checkpointDir`)
 * @param json 序列化器; 默认宽松解析 (忽略未知字段), 便于协议演进后读旧档
 */
class FileCheckpointStore(
    private val fileSystem: HarnessFileSystem,
    private val dir: String,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : CheckpointStore {

    /** 会话 id → 物理文件路径 (消毒后拼接, 永不逃出 [dir])。 */
    fun pathFor(sessionId: String): String = "$dir/${sanitizeCheckpointId(sessionId)}$SUFFIX"

    override suspend fun load(sessionId: String): Checkpoint? = try {
        json.decodeFromString(Checkpoint.serializer(), fileSystem.readText(pathFor(sessionId)))
    } catch (_: Exception) {
        // 不存在 / 不可读 / JSON 损坏 → 一律视为"无检查点" (fail-soft, 不中断任务)
        null
    }

    override suspend fun save(checkpoint: Checkpoint) {
        fileSystem.writeText(
            path = pathFor(checkpoint.sessionId),
            content = json.encodeToString(Checkpoint.serializer(), checkpoint),
            createParentDirs = true
        )
    }

    override suspend fun clear(sessionId: String) {
        try {
            fileSystem.delete(pathFor(sessionId))
        } catch (_: Exception) {
            // 删除失败不抛: 清理是尽力而为, 残留文件会被下次 save 覆盖
        }
    }

    /** 目录下全部检查点文件对应的会话 id (目录不存在返回空列表)。 */
    override suspend fun listSessionIds(): List<String> = try {
        fileSystem.listFiltered(dir, suffix = SUFFIX)
            .map { it.removeSuffix(SUFFIX) }
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val SUFFIX = ".json"
    }
}
