# Komiho 项目交接文档（2026-08-15）

> 本文件由 MihonSY 负责人整理，供 Komiho 新跟进助手使用。以后 **Komiho 由新助手全权负责**，MihonSY 负责人不再改动 Komiho 代码。

---

## 1. 项目是什么

**Komiho** = 多来源漫画阅读器（独立 Android 应用），支持 **Komga / 本地文件 / WebDAV / SMB** 四类来源（另有本地书签与历史），基于 MihonSY（TachiyomiSY fork）阅读器源码精简改造。

> ⚠️ 本段原写「纯 Komga 客户端」，为 2026-08-15 旧口径——Komiho 已演进为多来源客户端，KOMGA 只是其中一类来源。

**核心诉求**（用户原话）：
- 不用 Tachiyomi 插件/source 机制，按 **Komga 官方 API 重新编写数据层**
- **数据彻底同步**：已读/未读/tag/阅读进度 与 Komga 服务器实时双向同步
- **独立应用**（新包名，与 MihonSY 并存）
- **彻底简化**：移除 source/扩展体系

**关键决策（已定，勿推翻）**：
- Komga 是唯一真源，客户端**不存状态**（列表/已读/未读/tag 每次拉取服务器实时值）
- 进度写回：逐本 `PATCH /api/v1/books/{id}/read-progress` `{page, completed}`
- 应用名 **Komiho**
- API 认证：**API Key（`X-API-Key` header）优先**，Basic（账号密码）备选；**API Key 不需要用户名**

---

## 2. 仓库与路径

| 项 | 值 |
|---|---|
| GitHub 仓库 | `github.com/ruzhe85/mihonsy-komga`（**私有**） |
| 本地路径 | `C:\Users\KING\WorkBuddy\2026-08-11-20-52-20\mihonsy-komga\` |
| 推送方式 | **必须 SSH**：`git config core.sshCommand "ssh -i /c/Users/KING/.ssh/id_devman -o IdentitiesOnly=yes -o StrictHostKeyChecking=no"`（本机 HTTPS 不稳） |
| 初始基线 | MihonSY v1.0.5 源码快照（commit `3007125` 前的 initial import） |
| gh CLI | `export PATH="/c/Users/KING/WorkBuddy/2026-08-11-20-52-20/toolchain/gh_cli/bin:$PATH"` |

---

## 3. 当前进度（2026-08-15 11:02 确认）

### ✅ M1 完成（编译通过，未实机验证）
- **run `31858839705` success**
- komga-data 模块：DTO（Library/Series/Book/Page/ReadProgress/ReadingList，字段对照 openapi.json 1.26.3）+ `KomgaApiClient`（认证/库/系列/书/页/进度/列表）+ `KomgaPreferences`（SharedPreferences 持久化）
- UI：`KomgaConnectActivity`（连接页，API Key/账号密码双模式 + 测试连接）、`KomgaHomeActivity`（库 chips + 系列网格 + 未读角标）、`KomgaSeriesActivity`（元数据 + tag chips + 书列表：已读/进度%/未读）

### ✅ M2 编译通过（run `31860105399` success，未实机验证）
- `KomgaCover.kt`：认证封面加载（downloadBytes → bitmap）
- `KomgaReaderActivity.kt`：HorizontalPager 翻页阅读；打开恢复 readProgress.page；翻页 PATCH 写回；末页标记 completed
- 系列详情书行点击 → 阅读器；封面已接真实图

### 最新 commit 链（master）
```
85b1db6 fix(M2): reader compile — suspend in coroutine + rememberCoroutineScope
9295777 feat(M2): covers + simple reader with progress two-way sync
a89e0b5 fix(M1): smart-cast fix
2a19e23 fix(M1): compile errors — get() nullability + logcat extension
bb70a79 fix: force-add gradle-wrapper.jar
3007125 chore: name the app Komiho; M1 compile-verify workflow
4dc5e9d feat(M1): library browsing
f314431 feat(M1): komga-data module + connection setup screen
```
工作区干净（无未提交改动）。

---

## 4. 技术架构

```
app（Compose UI，包 app.mihonsy.komga.ui）
  ├─ KomgaConnectActivity  连接页
  ├─ KomgaHomeActivity     库切换 + 系列网格
  ├─ KomgaSeriesActivity   系列详情 + 书列表
  ├─ KomgaReaderActivity   阅读器（进度双向同步）
  └─ KomgaCover            认证封面加载
komga-data（新模块，OkHttp + kotlinx.serialization）
  ├─ model/   DTO（PageableDto 分页包装等）
  ├─ KomgaApiClient.kt    REST 客户端
  ├─ KomgaConnection.kt   认证配置
  └─ KomgaPreferences.kt  连接持久化
```

**关键 API 映射**（已按 openapi.json 1.26.3 核对）：
- 库浏览：`GET /libraries` → `GET /series?library_id=` → `GET /series/{id}/books`
- 筛选（全服务器端）：`GET /series?search=&genre=&tag=&read_status=...`
- 页图片：`GET /books/{id}/pages/{n}`（+`/raw`）
- 进度写回：`PATCH /books/{id}/read-progress` `{page, completed}`
- 已读/未读：`SeriesDto.booksReadCount/booksUnreadCount/booksInProgressCount`（服务器直接给）
- 阅读列表：`GET /readlists`、下载：`GET /books/{id}/file`
- 注意：**不存在 `GET /books/{id}/read-progress`**（进度在 book 详情内）——已从代码删除

---

## 5. 构建与验证

- **workflow**：`assembleDebug` + artifact 上传（`komiho-apk`，290MB 含各 ABI）——**编译验证模式**，无签名/无 Release（新仓库还没配 keystore secrets，到 M5 再配）
- Gradle 缓存已加（重建 6-8 分钟）
- 验证命令：
  ```bash
  gh run list --repo ruzhe85/mihonsy-komga --limit 1
  gh run download <run-id> --repo ruzhe85/mihonsy-komga   # 下载 debug APK
  ```
- **盯构建模式**：用户明确要求"盯着构建，报错直接修，不要每次通知"——push 后循环 `gh run list` 等 completed，失败就抓 `gh run view <run-id> --log-failed | grep -iE "e: file|error:|Unresolved"` 修完继续，成功才汇报

---

## 6. ⚠️ 环境坑（必须遵守）

1. **工作区文件会被外部进程反复批量删除**（mihonsy 的 res 目录被删了 3 次）。应对：
   - 恢复用 index 方式：`git checkout <parent-commit> -- <path>`（直写 index，不依赖工作区）
   - **只 `git add` 具体改动的文件，绝不 `git add 整个目录`**（工作区缺文件会用空覆盖 index）
   - 恢复+修改+提交+推送尽量放**一条提权命令**里，缩短暴露窗口
   - 提交后验证：`git ls-tree -r HEAD --name-only <dir> | wc -l` 对照 parent
2. **gradle-wrapper.jar**：`gradle/wrapper/.gitignore` 是 `*.jar`，必须 `git add -f` 强制追踪（曾因此构建失败）
3. 新仓库无 keystore secrets → 签名/Release 步骤留到 M5（用 mihonsy 的 keystore/mihonmod.jks 方案）

---

## 7. 待办（按里程碑）

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 连接+浏览 | komga-data + 连接页 + 库/系列/详情 | ✅ 编译过 |
| M2 阅读闭环 | 封面 + 阅读器 + 进度双向 + 已读未读角标 | ✅ 编译过（**需实机验证**） |
| M3 浏览增强 | 阅读列表页、全局搜索（search 参数）、tag/genre 筛选、系列详情完善 | 待做 |
| M4 下载 | `GET /books/{id}/file` 整本下载 + 离线阅读 | 待做 |
| M5 独立应用 | 新包名确认、移除 source 体系、签名/Release、图标/README | 待做 |

**近期优先**：
1. **实机验证 M1+M2**（连真实 Komga 服务器看库/系列/阅读/进度同步）——装 debug APK 到手机（arm64）
2. M3：底部导航 5 tab（主页/库/阅读列表/搜索/设置，对齐 Komga Web 语义）

**待定项**（到对应阶段再定）：
- PDF/EPUB 书支持（用户库里有没有 PDF 未确认；M2 只做了 CBZ/图像页）
- 下载走整本 zip 还是逐页缓存
- 新包名最终值（建议 `app.mihonsy.komga`）
- install_latest.sh（工作区根，MihonSY 用）指向的是 MihonSY 仓库，Komiho 需要时另配

---

## 8. 用户工作约定（对 Komiho 同样适用）

- **除非用户明确说"推送"，否则改动只本地 commit，不 push**（用户会先看代码再决定）
- 用户说"继续"才继续下一个里程碑；改完先汇报内容再等指令
- 用户不喜欢被频繁打扰；构建盯梢模式（见 §5）
- 交接后：**MihonSY 负责人不再碰 Komiho**，Komiho 相关需求直接找新助手
