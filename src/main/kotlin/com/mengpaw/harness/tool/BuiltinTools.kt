// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.harness.tool

import com.mengpaw.harness.HarnessEnv
import com.mengpaw.harness.HarnessToolInvoker
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * 内置基础工具集 — 让开发者 clone 后无需自造工具即可跑通第一个 Agent 循环。
 *
 * 这是 Harness 从"接口框架"变成"可用库"的关键一件: Python 侧同类库开箱即带
 * shell/文件/搜索工具, 否则使用者要先写一整套工具才有第一个可用调用。
 *
 * 设计约束:
 * - **只依赖 [HarnessEnv] 与标准库** — 不引入平台 API, 因此可跨平台复用。
 * - **沙箱友好**: 所有文件工具默认限制在 [HarnessEnv.paths] 的 `baseDir` 之内
 *   (越界路径拒绝), 避免模型读写宿主任意文件; 需要放宽时由宿主自行注册替代实现。
 * - **只读优先**: `file.read`/`file.ls`/`file.glob`/`env.get` 为 low 风险;
 *   `file.write` 为 mid 风险 (可逆); 高危能力 (执行命令/删除) 刻意不内置 —
 *   这类能力平台差异大且危险性高, 应由宿主显式提供并配自己的确认门。
 *
 * 用法:
 * ```
 * val env = HarnessEnv.jvmDefault(baseDir = "...")
 * val invoker = BuiltinTools.registry(env)   // ToolRegistry, 已装 6 个基础工具
 * ```
 */
object BuiltinTools {

    /** 单次读取的字节上限 — 防止大文件把上下文窗口一次性打满。 */
    const val DEFAULT_READ_LIMIT = 32_000

    /** glob 返回的条目上限。 */
    const val DEFAULT_GLOB_LIMIT = 200

    /**
     * 构造装载全部内置工具的注册表。
     *
     * @param env 平台环境 (文件系统 / 路径 / 确认门)
     * @param readLimit 单次读取字符上限
     * @param globLimit glob 结果条数上限
     */
    fun registry(
        env: HarnessEnv,
        readLimit: Int = DEFAULT_READ_LIMIT,
        globLimit: Int = DEFAULT_GLOB_LIMIT
    ): ToolRegistry = ToolRegistry(env).registerAll(
        *all(env, readLimit, globLimit).toTypedArray()
    )

    /** 内置工具列表 (不含注册表包装, 供宿主挑选部分注册)。 */
    fun all(
        env: HarnessEnv,
        readLimit: Int = DEFAULT_READ_LIMIT,
        globLimit: Int = DEFAULT_GLOB_LIMIT
    ): List<HarnessTool> = listOf(
        FileRead(env, readLimit),
        FileWrite(env),
        FileList(env),
        FileGlob(env, globLimit),
        EnvGet(),
        HttpGet(env)
    )

    // ── 路径安全 ────────────────────────────────────────────────

    /**
     * 解析并校验路径 — 越出 `baseDir` 一律拒绝。
     *
     * 模型可能生成 `../../etc/passwd` 之类路径, 故此处是硬边界而非建议:
     * 相对路径按 `baseDir` 解析, 绝对路径必须落在 `baseDir` 内。
     */
    private fun resolveInside(env: HarnessEnv, raw: String): String {
        val base = env.paths.baseDir.trimEnd('/')
        val cleaned = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (cleaned.isEmpty()) throw ToolArgsException("路径为空")
        val normalized = normalize(cleaned)
        val absolute = if (normalized.startsWith("/")) normalized else "$base/$normalized"
        val resolved = normalize(absolute)
        if (resolved != base && !resolved.startsWith("$base/")) {
            throw ToolArgsException("路径越出工作目录: '$raw' (仅允许 $base 之内)")
        }
        return resolved
    }

    /** 折叠 `.` 与 `..` 段 (不依赖平台路径 API, 保持跨平台一致)。 */
    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        val trailingSlash = path.length > 1 && path.endsWith("/")
        for (segment in path.split("/")) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast() else out.addLast("..")
                else -> out.addLast(segment)
            }
        }
        val joined = out.joinToString("/")
        return when {
            path.startsWith("/") -> if (trailingSlash && joined.isNotEmpty()) "/$joined/" else "/$joined"
            else -> joined
        }
    }

    // ── 工具实现 ────────────────────────────────────────────────

    /** `file.read <路径> [起始行] [行数]` */
    private class FileRead(private val env: HarnessEnv, private val limit: Int) : HarnessTool {
        override val name = "file.read"
        override val description = "读取文本文件内容; 可选指定起始行与行数, 便于分页查看大文件"
        override val parameters = "<路径> [起始行] [行数]"

        override suspend fun execute(input: ToolInput): ToolOutcome {
            val path = resolveInside(env, input.require(0, "路径"))
            if (!env.fileSystem.exists(path)) return ToolOutcome.fail("文件不存在: $path")
            if (env.fileSystem.isDirectory(path)) return ToolOutcome.fail("这是目录, 请用 file.ls: $path")

            val content = try {
                env.fileSystem.readText(path)
            } catch (e: Exception) {
                return ToolOutcome.fail("读取失败: ${e.message ?: "unknown"}")
            }

            val lines = content.lines()
            val start = input.arg(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val count = input.arg(2)?.toIntOrNull()?.coerceAtLeast(1)
            val slice = lines.drop(start - 1).let { if (count != null) it.take(count) else it }
            val text = slice.joinToString("\n")
            val truncated = text.length > limit
            val body = if (truncated) text.take(limit) + "\n…(已截断)" else text
            return ToolOutcome.ok(
                "文件: $path (第 $start 行起, 共 ${lines.size} 行)\n$body".trimEnd()
            )
        }
    }

    /** `file.write <路径> <内容>` — mid 风险 (可逆副作用)。 */
    private class FileWrite(private val env: HarnessEnv) : HarnessTool {
        override val name = "file.write"
        override val description = "写入(覆盖)文本文件, 自动创建父目录"
        override val parameters = "<路径> <内容>"
        override val riskLevel = "mid"

        override suspend fun execute(input: ToolInput): ToolOutcome {
            val path = resolveInside(env, input.require(0, "路径"))
            // 内容取原始行的剩余部分 — 保留内部空格与换行, 避免被参数切分破坏
            val content = input.raw.trim().split(Regex("\\s+"), limit = 2).getOrNull(1).orEmpty()
            if (content.isEmpty()) throw ToolArgsException("缺少内容参数")
            return try {
                env.fileSystem.writeText(path, content)
                ToolOutcome.ok("已写入 $path (${content.length} 字符)")
            } catch (e: Exception) {
                ToolOutcome.fail("写入失败: ${e.message ?: "unknown"}")
            }
        }
    }

    /** `file.ls <目录>` */
    private class FileList(private val env: HarnessEnv) : HarnessTool {
        override val name = "file.ls"
        override val description = "列出目录下的文件与子目录"
        override val parameters = "[目录]"

        override suspend fun execute(input: ToolInput): ToolOutcome {
            val path = if (input.arg(0).isNullOrBlank()) normalize(env.paths.baseDir)
            else resolveInside(env, input.arg(0)!!)
            if (!env.fileSystem.isDirectory(path)) return ToolOutcome.fail("目录不存在: $path")
            val entries = env.fileSystem.list(path).sorted()
            if (entries.isEmpty()) return ToolOutcome.ok("(空目录) $path")
            return ToolOutcome.ok(
                "$path (${entries.size} 项)\n" + entries.joinToString("\n") { "  $it" }
            )
        }
    }

    /** `file.glob <模式>` — 支持 `*` 与 `?`, 递归匹配 baseDir。 */
    private class FileGlob(private val env: HarnessEnv, private val limit: Int) : HarnessTool {
        override val name = "file.glob"
        override val description = "按通配模式查找文件(递归), 支持 * 与 ? 匹配文件名"
        override val parameters = "<模式> 例如 *.md 或 src/*.kt"

        override suspend fun execute(input: ToolInput): ToolOutcome {
            val pattern = input.require(0, "模式")
            val regex = globToRegex(pattern)
            val base = normalize(env.paths.baseDir)
            val matched = walk(base, regex, limit)
            if (matched.isEmpty()) return ToolOutcome.ok("无匹配: $pattern")
            return ToolOutcome.ok(
                "匹配 $pattern (${matched.size} 项)\n" + matched.joinToString("\n") { "  ${it.removePrefix("$base/")}" }
            )
        }

        private fun walk(dir: String, regex: Regex, limit: Int): List<String> {
            val result = mutableListOf<String>()
            val stack = ArrayDeque<String>().apply { addLast(dir) }
            while (stack.isNotEmpty() && result.size < limit) {
                val current = stack.removeLast()
                for (entry in env.fileSystem.list(current).sorted()) {
                    val full = "$current/$entry"
                    if (env.fileSystem.isDirectory(full)) stack.addLast(full)
                    else if (regex.matches(entry) || regex.matches(full)) result.add(full)
                    if (result.size >= limit) break
                }
            }
            return result
        }

        /** 通配转正则 — 仅支持 `*`(不含斜杠匹配多级) 与 `?`。 */
        private fun globToRegex(pattern: String): Regex {
            val sb = StringBuilder("(?i)")
            for (ch in pattern) {
                when (ch) {
                    '*' -> sb.append("[^/]*")
                    '?' -> sb.append("[^/]")
                    '.', '(', ')', '[', ']', '$', '^', '+', '|', '\\', '{', '}' -> sb.append('\\').append(ch)
                    else -> sb.append(ch)
                }
            }
            return Regex(sb.toString())
        }
    }

    /** `env.get [键名]` — 读环境变量; 无参列出全部键名 (不含值, 避免泄露敏感信息)。 */
    private class EnvGet : HarnessTool {
        override val name = "env.get"
        override val description = "读取环境变量; 不传参数则只列出键名(不显示值)"
        override val parameters = "[键名]"

        override suspend fun execute(input: ToolInput): ToolOutcome {
            val key = input.arg(0)
            if (key.isNullOrBlank()) {
                val names = System.getenv().keys.sorted()
                return ToolOutcome.ok("环境变量键名 (${names.size}):\n" + names.joinToString("\n") { "  $it" })
            }
            val value = System.getenv(key)
                ?: return ToolOutcome.fail("环境变量不存在: $key")
            return ToolOutcome.ok("$key=$value")
        }
    }

    /** `net.get <URL>` — 抓取网页/接口文本; mid 风险 (产生外联)。 */
    private class HttpGet(private val env: HarnessEnv) : HarnessTool {
        override val name = "net.get"
        override val description = "GET 请求一个 URL 并返回响应文本"
        override val parameters = "<URL>"
        override val riskLevel = "mid"

        override suspend fun execute(input: ToolInput): ToolOutcome {
            val url = input.require(0, "URL")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw ToolArgsException("URL 必须以 http:// 或 https:// 开头")
            }
            return try {
                val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
                try {
                    val body = client.get(url).bodyAsText()
                    ToolOutcome.ok("GET $url\n${body.take(DEFAULT_READ_LIMIT)}")
                } finally {
                    client.close()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutcome.fail("请求失败: ${e.message ?: e::class.simpleName ?: "unknown"}")
            }
        }
    }
}

/**
 * 便捷扩展 — 用内置工具直接构造 invoker。
 * 与 [BuiltinTools.registry] 等价, 便于链式书写。
 */
fun HarnessEnv.builtinToolInvoker(): HarnessToolInvoker = BuiltinTools.registry(this)
