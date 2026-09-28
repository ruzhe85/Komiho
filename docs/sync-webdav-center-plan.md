# Komiho 独立 WebDAV 同步中心（仅 WebDAV/SMB 阅读进度跨设备同步）方案

> 状态：设计定稿 v2（按用户反馈修订），待落地（Spike → 骨架 → 读取跳页 → 设置 UI → 真机验证）
> 范围：**仅 WebDAV + SMB 来源**的阅读进度跨设备同步。本地来源不同步（无稳定跨设备标识），Komga 来源不同步（已有服务器双向同步，避免冲突）。进度存到一个**独立配置**的 WebDAV「同步中心」，**不复用**现有 WebDAV 浏览连接列表。
> 原则：同步是独立功能；复用 Komga 进度回写范式与 `WebDavCredentialCrypto` / `WebDavRandomAccessSource.sharedHttpClient()`；写失败只 log，不阻塞阅读（对齐增强「黑帧禁止」哲学）。
> 入口：Komga 设置 → 备份与还原 → 新增「同步」设置项（见 §7）。

---

## 0. 目标与范围
- 用户在 Komga 设置 → 备份与还原 →「同步」里**独立配置一个** WebDAV 连接作为「同步中心」（与现有 WebDAV 浏览来源的连接列表互不相关、独立存储）。
- 仅 **WebDAV + SMB** 来源的书，阅读进度（page / 是否读完）写进该同步中心，多设备 / 重装可恢复。
- **不含** 本地来源（不同设备路径不一致，无法稳定标识同一本书，直接排除）。
- **不含** Komga 来源（Komga 自身已与 Komga 服务器双向同步，进同步中心会双写冲突，直接排除）。
- **不含** tag / 书签（文件系统无此概念）；**不含** 文件双向同步（备份 / 迁移），仅进度。

## 1. 总览（一张图）
```
WebDAV / SMB 书打开 / 翻页
   │
   ├─ 本地 DB：Manga.last_page_read（离线可读，照常）
   │
   └─ 同步中心（独立配置的 WebDAV）
        ├─ 打开书：GET komiho-sync/progress/<hash>.json
        │          → 与本地 last_page_read 取 updatedAt 较新者跳页
        └─ 翻页：PUT 同一文件（page / completed / updatedAt）
                  5s 节流 + 末页必写（复用 syncKomgaBookProgress 范式）
```
冲突策略：最后写入胜（updatedAt 较大者），无 merge（漫画阅读天然单线）。

## 2. 同步中心连接（独立，不复用现有 WebDAV 连接）
- **不复用** `WebDavConnection`（`app/mihonsy/komga/data/webdav/WebDavConnection.kt`）。同步是独立功能，单独建一套连接配置与存储，避免污染「浏览来源」连接列表、语义也更清晰。
- NEW `data/sync/SyncCenterConfig.kt`（`@Serializable`）：
  ```kotlin
  data class SyncCenterConfig(
      val baseUrl: String,        // 如 https://dav.example.com:10007/sync
      val user: String,           // 空串 = 匿名
      val passEnc: String,        // WebDavCredentialCrypto 加密形态（enc1: 前缀）
      val dirName: String = "komiho-sync",  // 进度目录名（普通可见，无点前缀）
      val enabled: Boolean = false,
  )
  ```
- NEW `data/sync/SyncCenterStore.kt`（`@Serializable` 单例，存 SharedPreferences 或文件）：
  - `get(): SyncCenterConfig?` / `save(cfg)` / `clear()`
  - `testConnection(cfg): Boolean`（PROPFIND 根，验证凭据 + 可达）
- 凭据 / Basic Auth / 加密：**完全复用** `WebDavCredentialCrypto`（与 WebDAV 浏览来源同算法，明文密码仅内存传递）+ `WebDavRandomAccessSource.sharedHttpClient()`（连接池 / 线程池共享）。
- 与现有 WebDAV 浏览连接唯一的「复用」仅限于加密工具和 HTTP 客户端这两类底层能力，连接数据彼此独立。

## 3. 书的稳定标识 bookKey（仅 WebDAV / SMB）
以其**章节 URL** 规范化后作为 bookKey 源（连接改名不改章节语义，connId 稳定）：
- WebDAV：`webdav://<connId>/<完整http(s) URL>`（`WebDavConnectionStore.CONN_URL_PREFIX`）
- SMB：`smb://<connId>/<共享内relPath>`（`SmbConnectionStore.CONN_URL_PREFIX`）
- 派生文件名：对 bookKey 做 `sha256` → hex（64 位），避免 URL / 路径非法字符与冲突。
- 本地 / Komga 来源不进入本流程（见 §0 / §6）。

## 4. 进度数据结构与存储布局
- 同步中心根下约定目录：`<dirName>/progress/`，默认 `komiho-sync/progress/`（`dirName` 普通可见名、**无需点前缀隐藏**，设置在同步页可改）。
- 每个书一个文件：`<root>/<dirName>/progress/<sha256(bookKey)>.json`
- `ProgressRecord`（`@Serializable`，放 NEW `data/sync/ProgressRecord.kt`）：
  ```kotlin
  { "bookKey": String, "page": Int, "completed": Boolean, "updatedAt": Long, "source": String }
  ```
- 写 = `PUT`（覆盖）；读 = `PROPFIND` 存在性 + `GET`；首建目录需 `MKCOL`（WebDAV `PUT` 通常不自动建中间目录，否则 409）。

## 5. 读写流程
- **读（打开书跳页）**：在 `ReaderViewModel` 加载章节、读本地 `last_page_read` 处，对 `webdav://` / `smb://` 来源的章，并行请求同步中心 `GET`；若拿到且 `updatedAt` 较新，则用同步中心 page 跳页（否则本地）。失败 / 缺失 / 未启用 → 用本地，静默。
- **写（翻页回写）**：在现有 `syncKomgaBookProgress`（约 `ReaderViewModel.kt` L1490，5s 节流 + 末页必写）旁，新增 `syncCenterProgress(bookKey, page, completed)`：
  ```kotlin
  // 仅当 chapter.url 以 webdav:// / smb:// 开头且同步中心已启用
  viewModelScope.launchNonCancellable {
      runCatching { RemoteProgressSync.write(bookKey, page, completed) }
  }   // 失败只 log，绝不阻塞阅读
  ```
  bookKey 从 `readerChapter.chapter.url` 取（与现有从 `chapter.url` 解析 bookId 同口径）。
- 抽象：`NEW data/sync/RemoteProgressSync.kt` 提供门面：
  ```kotlin
  object RemoteProgressSync {
      fun enabled(): Boolean                          // SyncCenterStore.get()?.enabled == true
      suspend fun read(bookKey: String): ProgressRecord?
      suspend fun write(bookKey: String, page: Int, completed: Boolean)
  }
  ```
  内部取 `SyncCenterStore.get()`，未启用 / 失败返回 null / 忽略。

## 6. 与各来源的关系
- WebDAV / SMB 书：进同步中心。判断依据：`chapter.url` 前缀 `webdav://` / `smb://`。
- 本地书：**不同步**（§0 已排除，无稳定跨设备 key）。
- Komga 书：维持原 `syncKomgaBookProgress` → Komga 服务器链路，**不**进同步中心（避免双写 / 冲突）。
- 三类来源本地 DB 都照常存 `last_page_read`，互不影响。

## 7. 文件改动清单
| # | 文件 | 改动 |
|---|---|---|
| 1 | NEW `data/sync/SyncCenterConfig.kt` | `@Serializable` 独立同步中心连接（baseUrl / user / passEnc / dirName / enabled） |
| 2 | NEW `data/sync/SyncCenterStore.kt` | 连接存取 + `testConnection()`（PROPFIND 根） |
| 3 | NEW `data/sync/ProgressRecord.kt` | `@Serializable` 进度记录 |
| 4 | NEW `data/sync/RemoteProgressSync.kt` | 启用判断 + read/write 门面 |
| 5 | NEW `data/sync/SyncCenterProgress.kt` | PROPFIND / GET / PUT / MKCOL 实现（复用 `WebDavRandomAccessSource.sharedHttpClient()` + `WebDavCredentialCrypto.decryptStored`） |
| 6 | `ui/reader/ReaderViewModel.kt` | 回写点新增 `syncCenterProgress`（仅 webdav:// / smb:// 且启用）；打开恢复处读同步中心取较新 |
| 7 | `presentation/more/settings/screen/SettingsKomihoBackupScreen.kt` | 备份与还原分组内新增「同步」设置项（跳转同步配置屏） |
| 8 | NEW `presentation/more/settings/screen/SettingsSyncScreen.kt` | 同步配置屏：连接 baseUrl / 用户 / 密码 / 测试连接、启用开关、目录名、清空同步数据 |
| 9 | 导航注册（KomgaMainActivity / SettingsMainScreen 跳转表） | 登记 SettingsSyncScreen 路由 |

## 8. 坑位与取舍
- WebDAV 同步中心可能禁 PUT / 只读 / 网盘异常（如 115）→ 写失败兜底，不阻塞阅读（对齐增强「黑帧禁止」哲学）。
- 中间目录需 `MKCOL` 预建（首次 `PUT` 前 `PROPFIND` 确认 + 缺失则 `MKCOL`，逐层建 `dirName` 与 `progress`），否则 409。
- 凭据：明文密码仅内存，复用 `WebDavCredentialCrypto`，禁止落日志（对齐 `WebDavCoverCache` / `SmbCoverCache` 约定）。
- `connId` 删除重建 → 旧进度 key 失配（同 Komga 同理，可接受；告知用户改连接会丢旧进度）。
- 多设备同时读同一书：最后写入胜，打开取较新 → 体验为「最近一次阅读位置」，符合预期；不做并发锁。
- 进度目录写在用户 WebDAV 上属**可见**目录（`komiho-sync`，可在同步页改名），设置页明确告知用户该目录会被创建；不隐藏、不偷偷写。

## 9. 落地顺序
1. **Spike**：在「同步」配置里填一个 WebDAV 连接，`testConnection` 通过后手动 `MKCOL` + `PUT` + `GET` 一个进度文件，验证凭据 / PUT 行为（尤其网盘类 WebDAV 的 PUT 语义）。
2. **骨架**：SyncCenterConfig + SyncCenterStore + ProgressRecord + RemoteProgressSync + SyncCenterProgress + 写回（翻页回写，复用节流 / 末页必写）。
3. **读取跳页**：打开 WebDAV / SMB 书恢复同步中心进度（取较新）。
4. **设置 UI**：SettingsKomihoBackupScreen 加「同步」入口 + SettingsSyncScreen（连接 / 开关 / 目录名 / 清空）。
5. **真机验证**（多设备 / 重装恢复）。

## 十、真机验证清单
- 设备 A 读某 WebDAV / SMB 书到第 N 页 → 同步中心 `<dirName>/progress/<hash>.json` 出现且 page=N。
- 设备 B（或重装 / 清 App 数据）打开同一书 → 跳到第 N 页。
- 末页 → completed=true 且必写。
- 同步中心连接禁用 PUT → 阅读不受影响，log 有写失败记录，无崩溃 / 黑屏。
- 多设备交替读 → 始终恢复到最近一次位置（updatedAt 较新胜）。
- 设置改目录名 / 清空 → 旧进度不再读取、可清理。
- 本地书 / Komga 书进度**不**写同步中心（确认排除生效）。

---

## 十一、备份功能扩展（v3，2026-09-28 重大修订：基于已有 KomihoBackup 自动化）

> ⚠️ **修订说明（探查后）**：原 v3 草案按「从零写 BackupExporter/Importer」设计，但代码探查发现
> Komiho **已实现** `app/mihonsy/komga/data/backup/KomihoBackup.kt` + `SettingsKomihoBackupScreen.kt`，
> 是一套完整的**手动备份/恢复**：导出 来源（Komga/SMB/WebDAV 含凭据）+ 非 Komga 的 书/章节/历史/书签/分类，
> 加密用自研 `KMH1` 容器（先 zip 再 AES/GCM 整体加密，密码 PBKDF2 派生；不填密码=明文 zip）。
> 这意味着之前纠结的「凭据跨设备」「历史书签用 URL 不用主键」「填密码=加密不填=明文」**全部已被解决并可复用**。
> 故 v3 **不再从零造轮子**，而是把这套已有的导出/导入**自动化到同步中心**，并按你的口径收窄范围。
> **不**复用 MihonSY 的 `eu.kanade.tachiyomi.data.sync.*` 框架（SyncManager/SyncService/GoogleDriveSyncService）。

### 现状复用 vs 仍需补（核心）
| 能力 | 现状（KomihoBackup.kt） | v3 还需做 |
|---|---|---|
| 来源（SMB/WebDAV/Komga 连接）含凭据备份/恢复 | ✅ 已做（导出 decryptStored 明文、导入按加密与否重加密落 Keystore，跨设备可恢复） | 沿用；你「排除 Komga 来源」指漫画数据，Komga 连接配置仍应备份（现状已如此） |
| 历史/书签跨设备匹配 | ✅ 已用 `mangaUrl+chapterUrl` 关联（`chapterIdByKey`），**不用本地自增 ID** | 收窄：当前覆盖「非 Komga」=含本地，需改为仅 SMB/WebDAV |
| 加密（填密码=加密 / 不填=明文） | ✅ `KMH1` 容器（PBKDF2+GCM），`writeBackupFile`/`readBackupBytes`/`isEncrypted` 齐备 | 直接复用，该密码即「首次同步密码框」 |
| 自动写/读同步中心 | ❌ 仅手动文件 picker | **新增** 自动 PUT/GET 到 `<dirName>/backup/` |
| 范围排除本地 | ❌ 当前含本地来源 | 收窄过滤条件 |

> 注：你预想「排除 Komga」指**漫画数据来源**（Komga 服务端即真相源，不同步其阅读记录），但 **Komga 连接配置（服务器地址/apiKey）仍应备份**——
> KomihoBackup 现状正是如此（备份 `komga_connection` 但不同步其服务端记录）。v3 沿用此口径。

### 方案架构
- 复用 `KomihoBackup` 生成/解析备份内容（单文件信封 `BackupEnvelope`，内部 `BackupPayload`）。
- 同步中心存**单个备份文件**：`<dirName>/backup/komiho-backup.<ext>`，`<ext>` 为 `zip`（方案①明文）或 `komiho`（方案②加密容器）。
- A：`KomihoBackup.exportBackup(context)` → `writeBackupFile(context, password, os)` 得字节 → PUT 到同步中心。
- B：GET 该文件 → `isEncrypted()` 判断 → 若加密且本地无缓存密码则弹框 → `readBackupBytes()` + `importBackup()` 自动恢复。
- 「首次同步密码框」的密码直接喂给 `KomihoBackup` 的 `password` 参数——方案①②统一入口**已天然支持**。

### 范围收窄（相对 KomihoBackup 现状，已落地 2026-09-28）
- 备份的漫画数据（书/章节/历史/书签/分类/收藏）来源集合改为 `{SMB, WebDAV}`，**排除本地、排除 Komga 数据**。
- 来源连接备份：**保留 Komga 连接配置** + **保留 SMB/WebDAV 连接**（即你要恢复的来源）。
- ⚠️ **关键事实（探查纠正）**：本地 / WebDAV / SMB **三者共用 `LocalSource.ID`**（见 `KomgaMainActivity.sourceIdForChapterUrl` 注释，
  "本地/WebDAV/SMB 三者共用 LocalSource.ID，只有 url 前缀能区分"）。**不存在 `SmbSource.ID` / `WebDavSource.ID` 常量**，
  不能靠 source id 过滤，只能靠**章节/书 URL 前缀**区分：`smb://`、`webdav://`、`webdav:`。
- 已实现：`buildPayload` 先取非 Komga 全集、建章节映射，再用 `isRemoteSourceUrl(url)`（前缀判断）收窄为仅 SMB/WebDAV；
  `localBookmarks` 同样按 `chapterUrl` 前缀过滤；`restoreLocalData` 的 URL 匹配逻辑无需改（恢复的本来就是带前缀的 SMB/WebDAV 书）。

### 改动清单（基于现有代码扩展）
| # | 文件 | 改动 |
|---|---|---|
| 1 | `app/mihonsy/komga/data/backup/KomihoBackup.kt` | 增加范围常量：漫画数据收窄为仅 SMB/WebDAV（排除本地+Komga 数据）；Komga 连接配置照常备份 |
| 2 | `app/mihonsy/komga/data/sync/SyncCenterBackup.kt`（NEW） | 门面：`pushBackup(context, password?)`（export→writeBackupFile→PUT 到 `<dirName>/backup/komiho-backup.<ext>`）、`pullAndRestore(context, cachedPwd?)`（GET→isEncrypted→readBackupBytes→importBackup）；复用 v2 同步中心 WebDAV 传输 |
| 3 | `app/mihonsy/komga/data/sync/SyncCenterConfig.kt`（v2 待建） | 增加可选 `backupPassword` 字段（方案②；方案①留空），复用 v2 同步中心连接作备份存放地 |
| 4 | `app/.../presentation/.../SettingsSyncScreen.kt`（v2 待建） | 复用 `SettingsKomihoBackupScreen.PasswordDialog` 交互，加「备份密码（可选）」「立即备份」「立即恢复」；启动时若同步中心已启用且本地无密码缓存则按需弹框 |
| 5 | 自动触发 | App 启动 / 进入同步设置：若同步中心启用且远端存在备份文件，自动 `pullAndRestore`（冲突策略见下）；写入节流/手动触发 |
| 6 | `SettingsKomihoBackupScreen.kt` | 可选：保留手动入口作兜底（与同步中心自动互不冲突） |

### 冲突 / 一致性策略（已落地，2026-09-28）
- 恢复：`KomihoBackup.importBackup` 已实现「较新胜」（非简单覆盖）：
  - 章节进度：备份 `updatedAt`(=`chapters.last_modified_at`) > 本地 `last_modified_at` 才用 `ChapterUpdate` 覆盖 `read`/`bookmark`/`lastPageRead`/`bookmarkPage`；
  - 历史：新增 `history.sq::getHistoryByChapterId` 取本地 `last_read`，备份 `lastRead` 不早于本地才 upsert 覆盖（`time_read` 传 0 避免与本地累计时长重复累加）；
  - 书签：维持「不存在才插入」。
  - `updatedAt` 字段已补进 `BkChapter`/`BkHistory`（`ignoreUnknownKeys=true` 兼容旧备份）。
- 来源去重：按 `host+share` / `baseUrl` 去重（KomihoBackup 当前直接覆盖写 `smb_connections_v1` / `webdav_connections_v1`）。

### 依赖
- **前置**：v2 同步中心连接基础设施（`SyncCenterConfig`/`SyncCenterStore`/`SyncCenterProgress` 的 PROPFIND/GET/PUT/MKCL）须先落地（见 §7/§9）。v3 复用其 WebDAV 传输，不在 v3 内重造。

### 落地顺序
1. v2 同步中心连接骨架 + 进度同步读写（§9）。
2. `KomihoBackup` 范围收窄：漫画数据仅 SMB/WebDAV（本地示例验证导出/导入仍正确）。
3. `SyncCenterBackup`：pushBackup / pullAndRestore（先方案①明文跑通）。
4. 设置 UI：同步中心配置屏加备份密码框 + 立即备份/恢复 + 自动触发。
5. 方案②：password 喂给 `writeBackupFile`/`readBackupBytes`，B 本地缓存密码自动解密（KMH1 容器已支持，仅需接 UI 与缓存）。
6. 真机验证（A 写 → B 自动恢复来源/历史/书签；方案② 输一次密码后自动）。

### 验证清单
- A 配 SMB/WebDAV 来源、读几本到某页、加书签 → 触发同步 → 同步中心 `<dirName>/backup/komiho-backup.<ext>` 出现。
- B 首次进入（同步中心已配、密码填/不填同 A）→ 自动恢复：来源列表出现 SMB/WebDAV 连接（可正常浏览）、历史跳对应页、书签在。
- 方案②：同步中心文件为 `KMH1` 密文；B 未输密码无法恢复，输一次后自动。
- 本地来源书 / Komga 数据**不**出现在备份（确认排除生效）。
- 重装/换机后 B 的来源凭据可用（经本机 Keystore 重加密落盘）。

---

*本文件本地存档，不随 `docs/` 推送（遵守 tooling-config.md）。*
