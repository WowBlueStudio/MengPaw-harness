// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*

/**
 * 模块级共享 LLM HTTP 客户端。
 *
 * **引擎选型 (Harness 抽离时变更, 2026-08-21)**: 原实现用 Ktor OkHttp 引擎 + okhttp3
 * `ConnectionPool` + `java.util.concurrent.TimeUnit` — 这套绑定 JVM/Android,
 * 且把 OkHttp 泄漏进依赖图。现改用 **Ktor CIO 引擎**: 纯 Kotlin 实现、无 JVM-only
 * 依赖、KMP 各目标均可用, 且不引入第三方 HTTP 栈。
 *
 * 共享的意义 (不变): 此前每个 provider 各自 new HttpClient — 会话/角色切换即重建连接池,
 * 每次切换重新 TCP+TLS 握手 (~2-4 RTT)。共享单例让所有 provider 复用同一连接池
 * (keep-alive), 会话切换不再握手。
 *
 * 超时语义:
 *   - connectTimeout 20s   (DNS+TCP+TLS)
 *   - socketTimeout 120s   (静默判定阈值 — 推理思考期 60s+ 无数据, 120s 仍留余量)
 *   - maxConnectionsCount 100 / 每端点 8 — 对齐原 OkHttp 池规格 (8 空闲 / 5 分钟保活)
 *   - 无 requestTimeoutMillis — 会误杀生成期 >120s 的流式响应
 *
 * 单例不 close — 进程生命周期共享, provider.close() 为 no-op。
 */
internal object LlmHttpClient {
    /** 默认引擎; 宿主若需替换 (如 Ktor 的其它引擎), 用 [withEngine] 构造。 */
    val ktor: HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 0
            endpoint {
                connectTimeout = 20_000
                connectAttempts = 2
                socketTimeout = 120_000
                maxConnectionsCount = 100
                maxConnectionsPerRoute = 8
                keepAliveTime = 300_000
                pipelineMaxSize = 20
            }
        }
    }

    /** 由外部引擎构造共享客户端 — 供宿主在特定平台上换用自选引擎 (测试可用 MockEngine)。 */
    fun withEngine(engine: HttpClientEngineFactory<*>): HttpClient = HttpClient(engine)
}
