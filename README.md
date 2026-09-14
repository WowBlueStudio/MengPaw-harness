# MengPaw Harness

[![License](https://img.shields.io/badge/license-AGPL--3.0--or--later%20OR%20LicenseRef--Commercial-blue)](LICENSE)

跨平台的 Agent Harness 核心 — 从 [MengPaw](https://github.com/WowBlueStudio/MengPaw) 抽离的 ReAct 引擎与平台抽象层。

**当前状态: A 阶段完成 (抽象层就位), 核心逻辑迁移进行中。** 详见 [`docs/migration-roadmap.md`](docs/migration-roadmap.md)。

## 这是什么

一个不绑定任何平台、任何 LLM 厂商、任何工具形态的 Agent 循环核心:

- **ReAct 循环骨架** — 思考/行动/观察迭代、并行工具批、循环检测、自适应步数扩展、上下文折叠、检查点
- **平台抽象层** — 文件系统 / 逻辑路径 / 时间源 / 日志 / 高危确认门 / 工具执行器
- **工具协议** — 工具即命令 (CLI 文本) 与原生 function-calling 均可接入, 由宿主决定

设计原则: **核心只认接口, 不认平台**。抽象层的每个签名都不出现 `java.*` / `android.*` 类型, 由构建门禁 `verifyNoPlatformTypes` 强制, 防止抽象层被逐步腐蚀回平台绑定。

## 模块布局

```
src/main/kotlin/com/mengpaw/harness/        ← 接口层 (零平台类型, 受门禁保护)
    HarnessFileSystem.kt       文件系统边界
    HarnessPathResolver.kt     逻辑路径 ↔ 物理路径解耦
    HarnessClock.kt            时间源 (可测性: 换假时钟)
    HarnessConfirmGate.kt      高危确认门 (fail-closed 默认)
    HarnessToolInvoker.kt      工具执行协议
    HarnessEnv.kt              平台能力聚合根
src/main/kotlin/com/mengpaw/harness/jvm/    ← 平台实现 (允许引用 java.*)
    JvmHarnessFileSystem.kt
    JvmHarnessClock.kt
```

## 快速开始 (JVM/Android)

```kotlin
val env = HarnessEnv.jvmDefault(
    baseDir = context.filesDir.absolutePath,   // JVM: 任意可写目录
    confirmGate = MyDialogConfirmGate          // 无 UI 宿主保持默认 (一律拒绝)
)
```

`HarnessEnv` 是核心向宿主索取的**全部**平台能力。除它之外, 核心不接触任何平台 API。

## 构建

```bash
./gradlew check          # 编译 + 测试 + 跨平台门禁
./gradlew verifyNoPlatformTypes   # 只跑铁律门禁
```

需要 JDK 17。

## 与 MengPaw 的关系

MengPaw (Android 微内核 Agent 框架) 是本仓库的**宿主之一**, 经适配器提供:

| 抽象 | MengPaw 实现 |
|---|---|
| `HarnessFileSystem` | `java.io.File` (Android/JVM 通用) |
| `HarnessPathResolver` | 中文目录布局 + 消毒规则 |
| `HarnessConfirmGate` | 弹窗确认总线 (`UserConfirmBus`) |
| `HarnessToolInvoker` | CLI 命令管线 (`Pipeline`) |

反向依赖不存在: 本仓库不依赖 MengPaw 的任何代码。

## 许可

双许可: 社区版 AGPL-3.0-or-later, 商业授权见 `LicenseRef-Commercial`。
提交即版权让渡 — 详见主仓库 PR 政策。

SPDX 标识 (所有新建 `.kt`/`.kts` 必须带此头):

```
// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
```
