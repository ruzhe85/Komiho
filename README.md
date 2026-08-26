# Komiho

<div align="center">

![Komiho](.github/readme-images/app-icon.png)

**纯 [Komga](https://github.com/gotson/komga) 漫画阅读客户端**

包名 `cn.ruzhe.komiho` ｜ 版本 1.0.6 (7) ｜ Android 8.0+

[中文](./README.md) | [English](./README.en.md)

</div>

---

## 简介

Komiho 是基于 [MihonSY](https://github.com/jobobby04/TachiyomiSY) / [Tachiyomi](https://github.com/mihonapp/mihon) 改造的 **Komga 专用漫画阅读器**。它砍掉了上游的多源 / 插件 / 追踪体系，**以 Komga 服务器作为唯一数据源**，把 MihonSY 成熟的阅读器能力直接对接 Komga API（v1.26.3）。

- 连接自托管的 Komga 服务器（URL + API-Key），支持多服务器切换
- 浏览库 / 系列 / 书，阅读进度实时回写 Komga
- 复用 MihonSY 完整阅读器（翻页 / 条漫 / RTL / 双页 / 缩放 / 图像增强）

> ⚠️ Komiho 需要你自建或已有可用的 **Komga 服务器**，本应用本身不提供漫画内容。

---

## ✨ 核心功能

### 1. 服务器连接（多 Komga）

- **连接管理**：通过 URL + API-Key（`X-API-Key`）连接自托管 Komga 服务器，连接前自动校验。
- **多服务器**：可添加多个 Komga 服务器，设置内一键切换激活；每条可编辑 / 删除。
- **无感迁移**：旧版单连接配置在首次启动自动迁移为单条连接，原有配置不丢。

### 2. 书库浏览

- **库 / 系列 / 书** 三层模型，对齐 Komga（Series = 书，Book = 章节）。
- 库列表（支持多库）、系列网格（封面来自 Komga thumbnail）、系列详情（书按序排列）。
- **Home 聚合**：Keep Reading / On Deck 等区块，语义对齐 Komga Web Dashboard。
- 顶栏库选择器（DropdownMenu）快速切换当前库。

### 3. 阅读器（MihonSY 全能力）

- 翻页（LTR / RTL / 垂直）、条漫（webtoon）、双击捏合缩放、双页。
- 阅读设置、进度记忆、滚轮 / 按键翻页等完整交互。
- **进度回写**：读到末页置 `completed`，book 级 + series 级（`/api/v2/series/{id}/read-progress/tachiyomi`）进度节流回写，Komga Web 端可见。

### 4. 图像增强（轻量）

| 算法 | 类型 | 档位 |
|------|------|------|
| **Lanczos3** | 经典插值 | 1.5x / 2x / 2.5x / 3x |

- 针对漫画 / 条漫线条优化，加载快、内存占用低，不含 waifu2x / Anime4K 等重型模型。
- 入口：设置 → 阅读器 → 图像增强。

### 5. 搜索与筛选

- 全局关键词搜索（`/api/v1/series?search=`）。
- 按 **标签 / 作者** 筛选；主页搜索范围为所有库，库内选择为当前库。
- 搜索结果复用库的显示模式（不再固定平铺）。

### 6. 设置与关于

- 外观（主题 / 语言）、阅读器、服务器连接（多服务器管理器）。
- **关于**页：显示版本号、检查更新（更新检查目标仓库 `ruzhe85/Komiho`）、GitHub 源码链接。

---

## 📦 构建

### GitHub Actions（推荐，本仓库已配置）

推送到 `main` 分支自动触发 `Build Komiho V2 APK` workflow，构建产物发布到固定 `CI` release tag：

```bash
git push origin main
# 下载最新 APK（arm64-v8a）
# https://github.com/ruzhe85/Komiho/releases/download/CI/komiho-ci-arm64-v8a.apk
```

- 产物：`komiho-ci-arm64-v8a.apk`（当前为单 ABI，CI 通道）。
- 签名：固定 debug keystore（经 GitHub Secrets `KOMIHO_DEBUG_KEYSTORE_BASE64` 注入，不落入代码库）。
- 依赖：JDK 17 + Android SDK + NDK 28.2 + CMake。

### 本地构建（不推荐）

```bash
# 需要 JDK 17、Android SDK、NDK 28.2.13676358、Gradle 9.6.1
./gradlew assembleRelease
```

---

## 🗂️ 项目结构

| 路径 | 说明 |
|------|------|
| `komga-data/` | Komga API 客户端（OkHttp + X-API-Key）、DTO、连接偏好 |
| `app/src/main/java/app/mihonsy/komga/ui/` | Komiho 自有 UI（连接 / Home / 系列 / 阅读器 / 设置） |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/` | 复用的 MihonSY 阅读器 |
| `i18n/` | 多平台资源（应用名 `Komiho` 等） |
| `.github/workflows/build.yml` | GitHub Actions 构建配置 |

---

## 📝 更新记录

详见 [CHANGELOG.md](./CHANGELOG.md)（[English](./CHANGELOG.en.md)）。

---

## ⚠️ 注意事项

- 仅用于个人学习与使用，请勿用于商业用途。
- 请遵守所阅读漫画的版权规定。
- 本应用需要可用的 Komga 服务器，且需自行承担服务器数据安全责任。
- 本 fork 与上游无关联，问题请在本仓库 Issue 讨论。

---

## 致谢

- [MihonSY (jobobby04)](https://github.com/jobobby04/TachiyomiSY) — 上游 fork 基础（阅读器能力）
- [Mihon](https://github.com/mihonapp/mihon) — 主项目（Tachiyomi 继任）
- [Komga (gotson)](https://github.com/gotson/komga) — 媒体服务器与 API
