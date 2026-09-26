// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

import kotlinx.serialization.Serializable

/**
 * 会话检查点 — 循环状态的最小充分快照, 用于**断点续跑**。
 *
 * 为什么需要: 库此前只有 [com.mengpaw.harness.engine.Conversation] (取历史/加消息),
 * 那只能让模型"记得", 不能让循环"接着跑" — 进程重启或长任务中断后步数、终止原因、
 * 最终答复全部丢失, 宿主无从判断该重跑还是续跑。本模型补的就是这一层。
 *
 * 刻意只存"恢复循环所需"的字段, 不存循环内的临时计数 (连续失败数、空响应计数、
 * 循环检测窗口) — 那属启发式状态, 重跑时重新累积即可, 存了反而制造伪精确。
 *
 * @param sessionId 会话标识 (宿主命名; 作为存储键, 实现须消毒后使用)
 * @param task 本会话的原始任务文本 (恢复时以它为准, 不依赖调用方重传)
 * @param step 已完成的步数 (恢复时从这里继续)
 * @param status 运行态 — 只有 [CheckpointStatus.RUNNING] 可续跑
 * @param messages 完整消息序列 (role/content), 恢复时回灌 [com.mengpaw.harness.engine.Conversation]
 * @param updatedAt 最后写入时间 (epoch millis) — 供宿主做过期清理
 * @param terminationReason 终止原因 (与 AgentResult.terminationReason 同源)
 * @param answer 最终答复 (正常完成时有值)
 */
@Serializable
data class Checkpoint(
    val sessionId: String,
    val task: String,
    val step: Int,
    val status: CheckpointStatus,
    val messages: List<CheckpointMessage>,
    val updatedAt: Long,
    val terminationReason: String? = null,
    val answer: String? = null
)

/** 检查点状态 — 决定宿主该如何处置。 */
@Serializable
enum class CheckpointStatus {
    /** 循环仍在进行 (中断/崩溃前写入) — 可续跑。 */
    RUNNING,

    /** 已正常完成 — 续跑无意义, 通常做归档后清理。 */
    COMPLETED,

    /** 异常终止 (步数耗尽/循环检测/模型错误) — 续跑与否由宿主策略决定。 */
    FAILED
}

/**
 * 一条消息 (role/content)。
 *
 * 不用 `Pair<String, String>` 是为序列化: Pair 需要自定义序列化器, 而检查点要能直接
 * 落盘/跨端传输, 用命名 data class 更稳 (协议演进时字段可增删)。
 */
@Serializable
data class CheckpointMessage(val role: String, val content: String)

/**
 * 检查点存储 — 平台能力 (落点由宿主决定), 经 [HarnessEnv.checkpoints] 注入。
 *
 * 定位: 与 [HarnessFileSystem] 同层 — 都是"核心要落盘"的抽象, 区别是文件系统管字节,
 * 本接口管循环状态。跨进程/跨设备续跑需要宿主实现真正持久的版本 ([FileCheckpointStore]
 * 是零依赖的落盘参考实现; SQLite / Redis / 远端存储由宿主自行实现)。
 *
 * 契约:
 * - **实现必须线程安全** (同一会话可能被并发读写)。
 * - [load] 取不到 (不存在/已损坏) 返回 null, 不抛异常; 其余方法失败可抛异常 —
 *   调用方 (ReActEngine) 会吞掉检查点异常并继续主任务, 检查点失败不得中断任务。
 * - 传入的 [Checkpoint.sessionId] 是**宿主提供的字符串**, 实现若用于路径/键拼接
 *   必须先消毒 (禁止目录穿越), 见 [FileCheckpointStore.pathFor]。
 */
interface CheckpointStore {

    /** 读取指定会话的检查点; 不存在返回 null。 */
    suspend fun load(sessionId: String): Checkpoint?

    /** 写入/覆盖检查点。 */
    suspend fun save(checkpoint: Checkpoint)

    /** 删除指定会话的检查点 (不存在即无操作)。 */
    suspend fun clear(sessionId: String)

    /**
     * 已知会话 id 列表 — 供宿主做"有无未完成任务"巡检。
     * 默认返回空列表 = 实现不支持枚举 (单键存储的宿主可以不管)。
     */
    suspend fun listSessionIds(): List<String> = emptyList()
}

/** 检查点会话 id 的最大长度 — 防止超长 id 撞文件系统上限 (落盘实现共用)。 */
internal const val CHECKPOINT_ID_MAX_LEN: Int = 64

/**
 * 会话 id 消毒 — 只保留 `[A-Za-z0-9_-]`, 其余替换为 `_`。
 *
 * 关键点: **点号也在替换之列**, 于是 `..` 无法存活 — 这是路径穿越的第一道闸
 * ([FileCheckpointStore] 拼路径前必调)。
 */
internal fun sanitizeCheckpointId(raw: String): String =
    raw.trim()
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(CHECKPOINT_ID_MAX_LEN)
        .ifBlank { "session" }
