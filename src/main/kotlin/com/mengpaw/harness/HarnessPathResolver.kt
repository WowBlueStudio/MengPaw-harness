// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness

/**
 * Harness 平台抽象 — 逻辑路径解析。
 *
 * 存在意义: Agent 的一切持久化都要落到具体位置, 而"具体位置"是平台相关的。
 * 若让核心代码直接拼字符串路径, 会同时带来三个问题:
 * ① 宿主必须理解框架的目录布局才能初始化;
 * ② 全局可变基路径使同进程无法运行两个独立实例 (多租户/测试隔离失效);
 * ③ 不同平台路径语义不一致 (Unix 无盘符、浏览器沙箱无真实路径)。
 *
 * 本接口把「逻辑位置」与「物理路径」解耦: 核心只请求 [configDir] / [agentDir] 这类
 * **逻辑位置**, 由宿主/实现决定映射到何种物理布局。
 *
 * 默认实现 [BaseDirPathResolver] 给出单一基目录下的标准布局, 目录名可整体替换
 * (中文档 / ASCII 档), 逻辑 API 不受影响。
 */
interface HarnessPathResolver {

    /** 数据根目录 — 所有内置路径的锚点。 */
    val baseDir: String

    /** 宿主可写输出目录 (面向用户的导出产物)。 */
    val outputDir: String

    val configDir: String
    val skillsDir: String
    val pluginDir: String
    val checkpointDir: String
    val recordingDir: String
    val errorDir: String

    /** 全部 Agent 工作区的父目录。 */
    val agentsDir: String
    /** 无主档案目录 (无 Agent 归属的数据落点)。 */
    val evolutionDir: String
    /** 跨实例互传共享目录。 */
    val fleetShareDir: String
    /** Agent 模板目录。 */
    val agentTemplatesDir: String
    /** IPC socket 路径 (仅 Unix 类宿主有意义; 非 Unix 宿主返回空串)。 */
    val socketPath: String

    /** 单个 Agent 的工作区根目录 ([agentName] 已消毒)。 */
    fun agentDir(agentName: String): String

    /** Agent 记忆目录 — 三轨记忆 (long / mid / project) 的物理落点。 */
    fun memoryDir(agentName: String): String

    /** Agent 对话归档目录 (上下文折叠前的原始 dialog)。 */
    fun dialogArchiveDir(agentName: String): String

    /** Agent 工具结果外存目录 (长输出离屏)。 */
    fun toolResultsDir(agentName: String): String

    /** Agent 本地技能目录。 */
    fun agentSkillsDir(agentName: String): String

    /** Agent 本地工具目录 (Agent 自建命令)。 */
    fun agentToolsDir(agentName: String): String

    /** Agent 进化档案目录 ([agentName] 为空时回落 [evolutionDir])。 */
    fun agentEvolutionDir(agentName: String?): String

    /** 路径段安全化 — 防路径穿越。所有接收外部 agentName 的实现必须走此函数。 */
    fun sanitizeSegment(raw: String): String
}

/**
 * 默认路径解析器 — 单一基目录下的标准布局。
 *
 * @param baseDir 数据根目录 (必填, 无隐式默认值 — 消除"忘记初始化"的隐患)。
 * @param outputOverride 用户可见输出目录; 留空则回落 `baseDir/输出`。
 * @param socketOverride IPC socket 路径; 留空则回落 `baseDir/mengpaw.sock`。
 * @param names 目录命名集 — 默认中文目录名 (与 MengPaw 既有布局逐字一致);
 *   对非中文路径敏感的宿主可传 [DirectoryNames.ASCII], 逻辑 API 不受影响。
 */
class BaseDirPathResolver(
    override val baseDir: String,
    outputOverride: String? = null,
    socketOverride: String? = null,
    private val names: DirectoryNames = DirectoryNames.CHINESE
) : HarnessPathResolver {

    /** 目录命名集 — 只影响物理目录名, 不影响任何逻辑路径 API。 */
    data class DirectoryNames(
        val config: String = "配置",
        val skills: String = "技能剧本",
        val pluginCache: String = "插件仓库",
        val checkpoints: String = "会话检查点",
        val recordings: String = "录音",
        val errors: String = "错误报告",
        val agents: String = "Agent文档",
        val evolution: String = "进化档案",
        val fleetShare: String = "Fleet共享",
        val agentTemplates: String = "agent-templates",
        val output: String = "输出",
        val socket: String = "mengpaw.sock"
    ) {
        companion object {
            val CHINESE = DirectoryNames()

            /** ASCII 目录名 — 供对中文路径敏感的宿主/CI 镜像使用。 */
            val ASCII = DirectoryNames(
                config = "config", skills = "skills", pluginCache = "plugins",
                checkpoints = "checkpoints", recordings = "recordings", errors = "errors",
                agents = "agents", evolution = "evolution", fleetShare = "fleet-share",
                agentTemplates = "agent-templates", output = "output", socket = "mengpaw.sock"
            )
        }
    }

    override val outputDir: String =
        outputOverride?.takeIf { it.isNotBlank() } ?: "$baseDir/${names.output}"

    override val socketPath: String =
        socketOverride?.takeIf { it.isNotBlank() } ?: "$baseDir/${names.socket}"

    override val configDir: String get() = "$baseDir/${names.config}"
    override val skillsDir: String get() = "$baseDir/${names.skills}"
    override val pluginDir: String get() = "$baseDir/${names.pluginCache}"
    override val checkpointDir: String get() = "$baseDir/${names.checkpoints}"
    override val recordingDir: String get() = "$baseDir/${names.recordings}"
    override val errorDir: String get() = "$baseDir/${names.errors}"
    override val agentsDir: String get() = "$baseDir/${names.agents}"
    override val evolutionDir: String get() = "$baseDir/${names.evolution}"
    override val fleetShareDir: String get() = "$baseDir/${names.fleetShare}"
    override val agentTemplatesDir: String get() = "$baseDir/${names.agentTemplates}"

    override fun agentDir(agentName: String): String = "$agentsDir/${sanitizeSegment(agentName)}"

    override fun memoryDir(agentName: String): String = "${agentDir(agentName)}/memory"

    override fun dialogArchiveDir(agentName: String): String = "${agentDir(agentName)}/dialog"

    override fun toolResultsDir(agentName: String): String = "${agentDir(agentName)}/tool_results"

    override fun agentSkillsDir(agentName: String): String = "${agentDir(agentName)}/skills"

    override fun agentToolsDir(agentName: String): String = "${agentDir(agentName)}/tools"

    override fun agentEvolutionDir(agentName: String?): String =
        if (agentName.isNullOrBlank() || agentName == DEFAULT_AGENT) evolutionDir
        else "${agentDir(agentName)}/evolution"

    override fun sanitizeSegment(raw: String): String = raw.replace(SEPARATOR_REGEX, "_")

    private companion object {
        /** 保留字: 无主档案的归属判据。 */
        const val DEFAULT_AGENT = "default"
        val SEPARATOR_REGEX = Regex("[/\\\\]")
    }
}
