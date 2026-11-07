# mixu-client

谜序（Mixu）的 Android 客户端：面向悬疑、推理小说的**离线优先阅读器**，附带一块只随阅读进度揭示信息的「案情板」。

> 帮读者记住线索，把推理留给读者。

本仓库是公开仓库。产品定位、需求编号、架构决策都在
[mixu-docs](https://github.com/mixu-app/mixu-docs)，本仓库只承载客户端实现与设备侧约束。

## 现状

**文档优先阶段，尚无产品代码。** 本仓库目前只包含仓库治理文件（`AGENTS.md`、
`repo.toml`、`justfile`、CI、`SECURITY.md`）与规划中的模块边界。里程碑定义见
[mixu-docs · 路线图](https://github.com/mixu-app/mixu-docs/blob/main/docs/roadmap-and-decisions.md)：
M0 阅读内核、M1 案情板、M2 托管 AI 内测、M3 联网 beta、M4 打磨。

计划中的模块布局（M0 首个任务落地时创建，此处只是契约，不是已实现）：

```text
app-android/                     # 应用壳、导航、依赖装配
shared/domain/                   # book/progress/graph/overlay/settings 模型
shared/feature-library/
shared/feature-reader-shell/
shared/feature-graph/
shared/feature-sync/             # 仅联网能力，P1
shared/persistence/              # SQLDelight schema 与仓库
shared/network/                  # 类型化 Ktor Client，不含任何云凭证
platform/reader-android/         # Readium EPUB 适配
platform/eink-android/           # 刷新策略适配（可选）
platform/secure-storage-android/ # Keystore 支持的凭据存储
```

技术基线（已决策，见 ADR-001/002/003/004）：Kotlin Multiplatform + Compose
Multiplatform 共享逻辑与大部分 UI；Readium Kotlin Toolkit 负责 EPUB 排版与导航；
SQLite + SQLDelight 负责本地库与图查询。**不要用 Compose Text 手写 EPUB 渲染。**

## 不可协商的边界

- **不登录也能读。** 导入、阅读、书签、笔记、已下载的图包在无账号、无网络、服务端
  不存在时都必须可用。任何把注册设成前置条件的改动都是回归。
- **同一个 APK 服务 E-Ink。** 只允许手动/建议式的显示策略切换，不做产品分叉。
- **剧透安全靠确定性过滤，不靠提示词。** 禁止"把整图渲染出来再在前端隐藏节点"。
- **客户端零云凭证。** 不含 AWS key、Cognito app client secret、价格目录或计费逻辑。
- **本地数据可导出。** 书签、笔记、个人叠加层的导出格式属于对外契约。

## 本地开发前提

工具链（缺什么补什么；用户环境允许用 pacman / uv / bun 自行安装）：

| 需要 | 用途 | 检查 |
|---|---|---|
| JDK 21 | Gradle/Kotlin 编译 | `java -version` |
| Android SDK（platform + build-tools） | 设备构建 | `sdkmanager --list_installed` |
| Gradle（M0 前通过 wrapper 固定版本） | 构建 | `./gradlew --version` |
| `just` | 统一质量门禁入口 | `just --version` |
| `markdownlint-cli2` | 文档门禁 | `markdownlint-cli2 --version` |

Gradle wrapper 会在 M0 的第一个任务里加入并在仓库内固定版本；此前 `just check`
会打印明确的阶段例外而不是静默通过。

## 质量门禁

```sh
just check     # markdownlint + (存在 wrapper 时) gradle check
just lint-md
```

门禁一律通过 `justfile` 调用，不要裸跑工具，CI 与本地走同一条路径。

## 参与贡献

先读 [`CONTRIBUTING.md`](CONTRIBUTING.md)。提交即表示你同意贡献按本仓库的 MPL-2.0
授权（DCO 签署，无 CLA）。剧透相关的改动必须带回归用
[fixtures](https://github.com/mixu-app/mixu-fixtures)；不允许提交书名正文、EPUB 文件
或任何在版权作品上抽取的图数据。

## 安全

公开仓库统一走 GitHub 私密漏洞报告（Security → Report a vulnerability），
见 [`SECURITY.md`](SECURITY.md)。剧透泄漏按信任缺陷处理。

## English

Mixu is an offline-first, Android-first mystery reader with a read-progress-aware
case board. This repository is the public KMP client. Documentation-first phase:
no product code exists yet.

## 依赖的核心库（Apache-2.0）

客户端把可复用的技术拆成独立仓库，以 Gradle 依赖方式引入。它们不是本仓库的一部分，
也不受本仓库 MPL-2.0 约束——别的项目可以单独复用它们：

| 仓库 | 内容 | 许可 |
|---|---|---|
| [mixu-graph](https://github.com/mixu-app/mixu-graph) | 时序叙事图模型、投影、查询 | Apache-2.0 |
| [mixu-spoiler](https://github.com/mixu-app/mixu-spoiler) | 知识边界过滤（anti-spoiler firewall） | Apache-2.0 |
| [mixu-renderer](https://github.com/mixu-app/mixu-renderer) | Compose Canvas 图谱渲染（稳定布局、增量动画、E-Ink） | Apache-2.0 |
| [mixu-contracts](https://github.com/mixu-app/mixu-contracts) | 图格式与 HTTP 契约 | Apache-2.0 |

## 许可证

本仓库（客户端）以 [MPL-2.0](LICENSE) 授权：**文件级 copyleft**——修改了已有文件的
分发版本需要公开这些文件的源码，但可以用新文件加入专有功能，也可以商业分发、上架应用
商店。选择它的原因：客户端最容易被整体闭源改造，而核心库已经 Apache-2.0 独立发布，
无需为了复用在客户端上妥协。

- 新增源文件带 SPDX 头：`// SPDX-License-Identifier: MPL-2.0`（Kotlin）、
  `# SPDX-License-Identifier: MPL-2.0`（脚本）。
- 第三方依赖清单与义务：[`THIRD-PARTY.md`](THIRD-PARTY.md)。
- 名称、Logo 与商标不在任何开源许可范围内，见
  [商标政策](https://github.com/mixu-app/.github/blob/main/TRADEMARKS.md)。
- 第三方图书内容永不随代码授权，详见 [`NOTICE`](NOTICE)。
