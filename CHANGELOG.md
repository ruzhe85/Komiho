# Komiho 更新公告 / Changelog

> Komiho 是基于 [MihonSY](https://github.com/jobobby04/TachiyomiSY) / [Tachiyomi](https://github.com/mihonapp/mihon) 改造的 **Komga 专用漫画阅读器**（砍掉多源 / 插件 / 追踪体系，Komga 为唯一数据源）。
> 版本号沿用改造基线 1.0.6；以下内容为 Komiho 自身的功能记录。

[中文](./CHANGELOG.md) | [English](./CHANGELOG.en.md)

## v1.0.6 (Komiho)

首个对外发布的 Komiho 版本，包含以下能力：

### 服务器连接
- 通过 URL + API-Key（`X-API-Key`）连接自托管 Komga 服务器，连接前自动校验。
- **多 Komga 服务器**：可添加多个服务器，设置内一键切换激活；每条可编辑 / 删除（删除确认）。
- 旧版单连接配置首次启动自动迁移为单条连接，配置不丢。
- 修：「重新配置连接」点击后误重进主页的 bug；「清除连接」改为仅清连接数据，不再误清显示 / 主题 / 语言等偏好。

### 书库浏览
- 库 / 系列 / 书三层模型，对齐 Komga（Series = 书，Book = 章节）。
- 库列表、系列网格（封面来自 Komga thumbnail）、系列详情（书按序排列）。
- Home 聚合 Keep Reading / On Deck，语义对齐 Komga Web Dashboard。
- 顶栏库选择器（DropdownMenu）快速切换当前库。

### 阅读器
- 复用 MihonSY 完整阅读器：翻页（LTR / RTL / 垂直）、条漫、双击捏合缩放、双页、阅读设置、进度记忆。
- 进度回写：读到末页置 `completed`，book 级 + series 级（`/api/v2/series/{id}/read-progress/tachiyomi`）节流回写。
- 修：阅读器 / 详情页误显示「在 WebView 打开 / 在浏览器打开 / 分享」按钮（Komga 为内部 scheme，无真实网页，已对 Komga 隐藏）；设置-阅读器底部按钮同步屏蔽这三项。

### 图像增强
- Lanczos3（1.5x / 2x / 2.5x / 3x），轻量无大模型。

### 搜索与筛选
- 全局关键词搜索。
- 按标签 / 作者筛选：主页搜索范围为所有库，库内选择为当前库；切 tab 自动重拉。
- 搜索结果复用库的显示模式（不再固定平铺 1 行 3 列）。

### 设置与关于
- 外观、阅读器、服务器连接（多服务器管理器）。
- **关于**页：显示版本号、检查更新、GitHub 源码链接（更新检查目标仓库 `ruzhe85/Komiho`）。
