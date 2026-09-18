// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    `maven-publish`
}

// 发布坐标 — JitPack 以 git tag 覆盖版本; group 由设置处统一下发
group = providers.gradleProperty("harness.group").orElse("com.github.WowBlueStudio.MengPaw-harness").get()
version = providers.gradleProperty("harness.version").orElse("0.1.0").get()

// ── 发布配置 (JitPack / 本地 mavenLocal) ──────────────────────────────
// 与 MengPaw 主仓库 mengpaw-kernel 保持同一套发布方式 (那套已在 JitPack 验证可用),
// 降低两仓库的发布差异面。发布纪律: tag / push 需用户明确指令, 不得自行发版。
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("MengPaw Harness")
                description.set("跨平台 Agent Harness 核心 — ReAct 循环 / 平台抽象层 / 工具协议")
                url.set("https://github.com/WowBlueStudio/MengPaw-harness")
                licenses {
                    license {
                        name.set("AGPL-3.0-or-later OR LicenseRef-Commercial")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                    }
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    // ReAct 循环为挂起函数 — 协程是核心硬依赖
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // 工具调用/会话协议的对外序列化
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ── LLM 客户端传输层 ──
    // 引擎选 CIO 而非 OkHttp: 纯 Kotlin 实现, 无 JVM-only 依赖 (okhttp3 + java.util.concurrent),
    // KMP 各目标可用。宿主若需换引擎, 用 LlmHttpClient.withEngine(...) 或自建 HttpClient。
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
}

/**
 * 跨平台铁律门禁 — harness 核心源码禁止引用任何平台类型。
 *
 * 只检查接口层 (`com/mengpaw/harness` 顶层); `.jvm` 子包是**平台实现**,
 * 允许 (且必须) 引用 java.*。任何新增的核心文件一旦引入平台依赖, 本门禁失败,
 * 从而防止"抽象层被慢慢腐蚀回平台绑定"这一最常见的退化路径。
 */
val verifyNoPlatformTypes by tasks.registering {
    group = "verification"
    description = "断言 harness 核心源码零平台类型引用 (java/javax/android/dalvik)"
    val coreDir = layout.projectDirectory.dir("src/main/kotlin/com/mengpaw/harness")
    inputs.dir(coreDir)
    doLast {
        // 接口层文件 = 核心目录下直接子文件 (排除 jvm 等平台实现子包)
        val forbidden = Regex("""^\s*import\s+(java|javax|android|androidx|dalvik)\.""")
        val offenders = coreDir.asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> forbidden.containsMatchIn(line) }
                    .map { (idx, line) -> "${file.name}:${idx + 1}: ${line.trim()}" }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "harness 核心出现平台类型引用 (违反跨平台铁律):\n  " + offenders.joinToString("\n  ")
            )
        }
        logger.lifecycle("verifyNoPlatformTypes: 通过 — 核心零平台类型引用")
    }
}

tasks.named("check") { dependsOn(verifyNoPlatformTypes) }
