// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

import kotlinx.serialization.json.Json

/**
 * 文件检查点存储 — **每份检查点一个 JSON 文件**。
 *
 * 这是**跨进程断点续跑**的参考实现: 落盘一律经 [HarnessFileSystem] (唯一边界),
 * 因此零平台类型, 任意宿主 (Android / 桌面 / 未来 KMP 目标) 都能直接用。
 * 典型接法: `FileCheckpointStore(env.fileSystem, env.paths.checkpointDir, keep = 3)`。
 *
 * ## 文件命名与"前缀歧义"修复
 * - 每步一份: `{dir}/{sessionId 消毒后}__step_{step}.json` (**双下划线分隔**)。
 *   早期实现只落 `{dir}/{sessionId}.json` 单档, 于是 `listSessionIds()` 只能靠
 *   `removeSuffix(".json")` 猜会话 id — 那是"猜"而不是"解析": 会话 id 本身以
 *   `.json` 结尾、或目录里混入任意 `*.json`, 都会被误当成会话。且单档意味着
 *   历史只留最后一次, 中途损坏即无档可续。
 * - [load] 的定位规则: 扫目录 → 文件名**精确解析**出 `(键, 步)` → 解析 JSON 内容 →
 *   **只有当 `checkpoint.sessionId == 请求的 sessionId` 时才采纳** (内容权威, 文件名只做定位;
 *   消毒是有损映射, `a.b` 与 `a_b` 同形, 逆推不可行)。于是对话会 id `a` 与 `ab`
 *   互不污染 — 这是本类此前靠 `removeSuffix` 猜不出来的。
 * - 早期单档 `{dir}/{sessionId}.json` **仍可读** (兼容), 但新写入一律带步数后缀。
 *
 * ## 保留策略
 * [save] 之后清理同会话旧档, 使该会话最多留 [keep] 份。
 * `keep = 0` (默认) = **不清理**, 与引入本参数前的行为逐字一致 (向后兼容);
 * 需要控制磁盘占用的宿主显式传 `keep = 3` (与 MengPaw kernel 侧
 * `CheckpointManager.DEFAULT_KEEP_COUNT = 3` 对齐)。
 * 清理排序用**文件系统时间戳 + 步数** (不读档内 `updatedAt` — 为清理再解析一遍全部档不划算),
 * 而"最近写入"在时序上与 `updatedAt` 单调一致; 步数是兜底 (同一毫秒内多次落盘时仍取最新步)。
 * 删除一律发生在"新档已落盘"之后, 且**永不动刚写入的那份** → 任意时刻至少有一份完整档。
 *
 * 安全: 会话 id 拼路径前先经 [sanitizeCheckpointId] 消毒 (点号被替换, `..` 无法存活),
 * 拼出的路径永远在 [dir] 内。**不要把未经本类处理的 id 拿去自行拼路径。**
 *
 * 读侧 fail-soft: 文件不存在或内容损坏时 [load] 返回 null (视为无检查点), 由引擎决定
 * 是否新开会话 — 检查点损坏不应让任务直接失败。
 *
 * @param fileSystem 文件系统抽象 (由宿主注入)
 * @param dir 检查点目录 (通常取 `HarnessPathResolver.checkpointDir`)
 * @param keep 保留份数 (按文件系统时间戳 + 步数保留最近 N 份); 0 = 不自动清理 (向后兼容默认)
 * @param json 序列化器; 默认宽松解析 (忽略未知字段), 便于协议演进后读旧档
 */
class FileCheckpointStore(
    private val fileSystem: HarnessFileSystem,
    private val dir: String,
    private val keep: Int = 0,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : CheckpointStore {

    /** 新格式物理路径 `{dir}/{消毒 id}__step_{step}.json` (消毒后拼接, 永不逃出 [dir])。 */
    fun pathFor(sessionId: String, step: Int): String =
        "$dir/${fileNameFor(sessionId, step)}"

    /**
     * 早期单档路径 `{dir}/{消毒 id}.json` — 只用于读取兼容, 不再写入。
     * 保留此公开方法是因为它同时是"消毒后不逃出目录"的可见证据 (既有测试依赖)。
     */
    fun pathFor(sessionId: String): String = "$dir/${sanitizeCheckpointId(sessionId)}$SUFFIX"

    /** 读取该会话 `updatedAt` 最新的一份; 不存在 / 全部损坏 → null。 */
    override suspend fun load(sessionId: String): Checkpoint? {
        // 以档内 sessionId 为准判归属 — 消毒有损, 文件名只做定位 (前缀歧义由此根除)
        val decoded = try {
            collectRaw(sessionId).mapNotNull { file ->
                val checkpoint = decode(file) ?: return@mapNotNull null
                if (checkpoint.sessionId != sessionId) return@mapNotNull null
                file to checkpoint
            }
        } catch (_: Exception) {
            return null
        }
        return decoded.maxWithOrNull(
            compareBy({ it.second.updatedAt }, { it.first.modified }, { it.first.step })
        )?.second
    }

    /** 写入一份检查点; 写入成功后按 [keep] 清理同会话旧档。 */
    override suspend fun save(checkpoint: Checkpoint) {
        fileSystem.writeText(
            path = pathFor(checkpoint.sessionId, checkpoint.step),
            content = json.encodeToString(Checkpoint.serializer(), checkpoint),
            createParentDirs = true
        )
        if (keep > 0) prune(checkpoint.sessionId, checkpoint.step)
    }

    /** 删除该会话的全部检查点 (新格式 + 早期单档); 不存在即无操作。 */
    override suspend fun clear(sessionId: String) {
        try {
            val paths = collectRaw(sessionId).map { it.path } + pathFor(sessionId)
            paths.distinct().forEach { path ->
                try {
                    fileSystem.delete(path)
                } catch (_: Exception) {
                    // 单个删除失败不抛: 清理是尽力而为, 残留会被下次 save/clear 覆盖或再删
                }
            }
        } catch (_: Exception) {
            // 枚举失败同样不抛 — 清理失败不该中断调用方的收尾流程
        }
    }

    /**
     * 目录下全部会话 id (目录不存在返回空列表)。
     *
     * 会话 id 取自**解析出的文件名键** (内容不可用时也可枚举) — 不再 `removeSuffix` 猜。
     * 同一会话的多份步数档会归并为一个 id。
     */
    override suspend fun listSessionIds(): List<String> = try {
        fileSystem.listFiltered(dir, suffix = SUFFIX)
            .mapNotNull { entry -> parseFileName(entry)?.key }
            .distinct()
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    /** 该会话的全部候选档 (含早期单档), 不做内容过滤。 */
    private fun collectRaw(sessionId: String): List<CheckpointFile> {
        val key = sanitizeCheckpointId(sessionId)
        return fileSystem.listFiltered(dir, suffix = SUFFIX)
            .mapNotNull { name ->
                val parsed = parseFileName(name) ?: return@mapNotNull null
                if (parsed.key != key) return@mapNotNull null
                val path = "$dir/$name"
                CheckpointFile(path, parsed.step, fileSystem.lastModified(path))
            }
            .sortedByDescending { it.step }
    }

    /** 解析单档内容, 失败返回 null (损坏档不参与比较, 由调用方决定降级)。 */
    private fun decode(file: CheckpointFile): Checkpoint? = try {
        json.decodeFromString(Checkpoint.serializer(), fileSystem.readText(file.path))
    } catch (_: Exception) {
        null
    }

    /**
     * 保留 [keep] 份, 删更旧的; [justSavedStep] 永不删 — 删除永远发生在新档落盘之后,
     * 于是任意时刻至少有一份完整档可读 (抽象层无 move 语义时的原子性替代)。
     *
     * 排序依据是**文件系统时间戳 + 步数** (不读内容): 为清理再解析一遍全部档不划算,
     * 而"最近写入"在时序上与 `updatedAt` 单调一致。
     */
    private fun prune(sessionId: String, justSavedStep: Int) {
        val victims = try {
            collectRaw(sessionId)
        } catch (_: Exception) {
            return
        }.filter { it.step != justSavedStep }
            .sortedWith(
                compareByDescending<CheckpointFile> { it.modified }
                    .thenByDescending { it.step }
            )
            .drop(keep - 1)
        victims.forEach { file ->
            try {
                fileSystem.delete(file.path)
            } catch (_: Exception) {
                // 尽力而为: 删不掉只是多占空间, 不得让 save 失败
            }
        }
    }

    private companion object {
        const val SUFFIX = ".json"
    }
}

/**
 * 文件名解析结果 — [key] 是消毒后的会话键, [step] 是步数 (早期单档为 -1)。
 * [key] 与原 sessionId 之间是**有损**关系, 只能用于定位, 不能反向当作会话 id 使用。
 */
private data class ParsedName(val key: String, val step: Int)

/** 候选档: 路径 / 步数 / 文件系统时间戳。 */
private data class CheckpointFile(
    val path: String,
    val step: Int,
    val modified: Long
)

/**
 * 文件名 → [ParsedName]; 不是检查点档 (或键为空) 返回 null。
 *
 * 支持两种形态: `{键}__step_{数字}.json` (现行) 与 `{键}.json` (早期单档)。
 * 用双下划线切分, 与会话键内部的下划线可辨; `lastIndexOf` 从右侧切, 键自身含
 * `__step_` 时也能切对。
 */
private fun parseFileName(fileName: String): ParsedName? {
    if (!fileName.endsWith(".json") || fileName.length <= ".json".length) return null
    val stem = fileName.substring(0, fileName.length - ".json".length)
    if (stem.isBlank()) return null
    val sep = stem.lastIndexOf("__step_")
    if (sep <= 0) return ParsedName(stem, LEGACY_SINGLE_FILE_STEP)
    val digits = stem.substring(sep + "__step_".length)
    val step = if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) digits.toIntOrNull() else null
    return step?.let { ParsedName(stem.substring(0, sep), it) }
}

/** 新格式文件名 = `{消毒 id}__step_{step}.json`。 */
private fun fileNameFor(sessionId: String, step: Int): String =
    "${sanitizeCheckpointId(sessionId)}__step_$step.json"

/** 早期单档没有步数概念 — 用 -1 占位 (排序时最旧)。 */
private const val LEGACY_SINGLE_FILE_STEP = -1
