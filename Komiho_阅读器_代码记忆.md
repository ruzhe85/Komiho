# Komiho V2 · 阅读器代码记忆

> 用途：阅读器相关源码的架构/关键类/调用链/已做与待做 速查。仿 `Komiho_媒体库_代码记忆.md` 风格。
> 仓库：`ruzhe85/komihoV2`（路径 `C:\Users\KING\WorkBuddy\2026-08-11-20-52-20\komihoV2`）
> 更新：2026-08-20

---

## 0. 一句话结论

Komiho V2 的"阅读器"**不是自写**，而是直接复用 MihonSY 的原生 `ReaderActivity` + `ReaderViewModel` + Pager/Webtoon Viewer 全套。Komiho 只在**接入层**做增量：

- `KomgaReaderLauncher`：把 Komga book 解析成 manga/chapter 落本地库，再调原生 ReaderActivity；
- `KomgaDbBridge.applyReadingMode`：按 Komga 系列 `readingDirection` 自动设阅读模式；
- `ReaderViewModel.syncKomgaBookProgress`：翻页即把"读到第几页"实时回写 Komga；
- `ReaderPreferences` 的图像增强（Lanczos3 等）与 `SettingsReaderScreen`（已挂进 Komiho 设置页）是 MihonSY 自带能力，直接复用。

> ⚠️ "Komelia 重写 S0–S3" 是 Komiho 总体**路线规划**（见全局记忆），**尚未落入本仓库**。本仓库当前是 MihonSY 阅读器原样复用 + 上面的增量。下面 §6 单列该路线，避免误判。

---

## 1. 目录结构（阅读器相关）

```
app/src/main/java
├── app/mihonsy/komga/
│   ├── ui/KomgaReaderLauncher.kt            ★ Komiho 入口：book → 原生 Reader
│   └── data/KomgaDbBridge.kt                ★ ensureManga/ensureChapters/applyReadingMode
├── eu/kanade/presentation/
│   ├── more/settings/screen/SettingsReaderScreen.kt   ★ 阅读设置页（已挂进 Komiho SettingsTab）
│   └── reader/…                              Compose 展示层（appbars / settings / components）
│       ├── settings/ReaderSettingsDialog.kt, ReadingModePage.kt, ColorFilterPage.kt, GeneralSettingsPage.kt
│       ├── appbars/ReaderAppBars.kt, ReaderTopBar.kt, ReaderBottomBar.kt
│       └── ChapterListDialog.kt, OrientationSelectDialog.kt, ReadingModeSelectDialog.kt …
└── eu/kanade/tachiyomi/
    ├── ui/reader/
    │   ├── ReaderActivity.kt                ★ 原生阅读器 Activity（入口）
    │   ├── ReaderViewModel.kt               ★ 状态机 + 进度回写（含 syncKomgaBookProgress）
    │   ├── setting/
    │   │   ├── ReaderPreferences.kt         ★ 阅读偏好（含 enhancementMode 增强）
    │   │   ├── ReadingMode.kt               ★ ReadingMode 枚举（LTR/RTL/VERTICAL/WEBTOON…）
    │   │   ├── ReaderOrientation.kt, ReaderBottomButton.kt, ReaderSettingsScreenModel.kt
    │   ├── loader/  PageLoader / HttpPageLoader / ArchivePageLoader / EpubPageLoader / DownloadPageLoader / DirectoryPageLoader / ChapterLoader
    │   ├── model/   ReaderPage / ReaderChapter / ReaderItem / ViewerChapters / ChapterTransition
    │   └── viewer/
    │       ├── pager/   PagerViewer / PagerPageHolder / PagerConfig / PagerViewers（L2R/R2L/Vertical）
    │       ├── webtoon/ WebtoonViewer / WebtoonPageHolder / WebtoonLayoutManager / WebtoonSubsamplingImageView
    │       └── navigation/  EdgeNavigation / LNavigation / RightAndLeftNavigation / KindlishNavigation / DisabledNavigation
    └── data/coil/TachiyomiImageDecoder.kt, util/MihonSyEnhancer.kt   ★ 图像解码 + 增强（Lanczos/Anime4K native）
```

---

## 2. 关键类与职责

| 类 | 路径 | 职责 |
|---|---|---|
| **KomgaReaderLauncher** | `app/mihonsy/komga/ui/` | 唯一 Komiho 阅读入口。`open(ctx, client, bookId)`：`getBook`→`getSeriesDetail`→`ensureManga`→`applyReadingMode`→`ensureChapters`→ 找到 `url==komga://book/{id}` 的 chapter → `startActivity(ReaderActivity, mangaId, chapterId)`。 |
| **KomgaDbBridge** | `app/mihonsy/komga/data/` | Komga 数据 ↔ Mihon DB 的桥。`ensureManga` / `ensureChapters` / `applyReadingMode`。 |
| **ReaderActivity** | `eu/kanade/tachiyomi/ui/reader/` | 原生阅读器 Activity。`onCreate` 取 `manga`/`chapter`/`page` extra → `viewModel.init(...)` → 渲染 `ReaderScreen`。 |
| **ReaderViewModel** | `ui/reader/ReaderViewModel.kt` | 阅读状态机：`init`→`loadChapter`→`onPageSelected`→`updateChapterProgress`→`syncKomgaBookProgress`。含 Komga 进度回写（R-3）。 |
| **KomgaSource** | `app/mihonsy/komga/source/` | 内嵌 `HttpSource`（id=1000001）。`getPageList`：每页 `imageUrl = client.pageImageUrl(bookId, n)`，带 `X-API-Key`；`BOOK_URL_PREFIX="komga://book/"`。 |
| **ReaderPreferences** | `ui/reader/setting/` | 阅读偏好。`enhancementMode`（0 Off /1 Anime4K /2 Lanczos3 /3 Catmull-Rom /4 Spline36，Anime4K 在 UI 隐藏）、`imageScaleType`、`doubleTapAnimSpeed` 等。 |
| **ReadingMode** | `ui/reader/setting/ReadingMode.kt` | 枚举：`LEFT_TO_RIGHT(0x1)` / `RIGHT_TO_LEFT(0x2)` / `VERTICAL(0x3)` / `WEBTOON(0x4)` / `CONTINUOUS_VERTICAL(0x5)`；`MASK=0x7`，`toViewer()` 映射到 Pager/Webtoon Viewer。 |
| **SettingsReaderScreen** | `presentation/more/settings/screen/` | MihonSY 完整阅读设置 Voyager Screen（翻页/方向/缩放/增强/条漫/导航/操作/下载）。**已挂进 Komiho `SettingsTab`**（见 §5）。 |
| **MihonSyEnhancer** | `tachiyomi/util/` | native 图像增强：Anime4K（GLES shader，实时 GPU）/ Lanczos3·Catmull-Rom·Spline36（CPU resample，`nativeLanczosProcess`/`nativeResample`）。 |
| **HttpPageLoader** | `ui/reader/loader/` | 按 `imageUrl` 拉图（Komga 图已是最终 URL，不走二次解析）。 |

---

## 3. 调用链（点开一本书 → 翻页 → 回写）

```
用户点书 (BookShelf)
  └─ KomgaReaderLauncher.open(ctx, client, bookId)            [suspend]
       ├─ client.getBook(bookId)
       ├─ client.getSeriesDetail(seriesId)
       ├─ KomgaDbBridge.ensureManga(...)        → Manga (落库)
       ├─ KomgaDbBridge.applyReadingMode(manga, readingDirection)   ← 自动阅读模式 (R-5)
       ├─ KomgaDbBridge.ensureChapters(...)     → List<Chapter> (落库)
       ├─ 取 chapter where url == "komga://book/{bookId}"
       └─ startActivity(ReaderActivity, manga.id, chapter.id)

ReaderActivity.onCreate
  └─ viewModel.init(mangaId, chapterId, page?)
       └─ loadChapter → KomgaSource.getPageList(chapter)
            └─ 每页 Page(imageUrl = client.pageImageUrl(bookId, n))  + X-API-Key
                 └─ HttpPageLoader 拉图 → TachiyomiImageDecoder 解码（可选 MihonSyEnhancer 增强）

用户翻页
  └─ Viewer.onPageSelected → ReaderViewModel.onPageSelected(page, ...)
       └─ updateChapterProgress(readerChapter, page, hasExtraPage)
            ├─ syncKomgaPageProgress(...)        （R-3 早期 partial 同步，节流）
            ├─ syncKomgaBookProgress(...)         ★ 见 §4 回写逻辑
            ├─ 末页 → updateChapterProgressOnComplete（标记 read / 触发 track 同步）
            └─ updateChapter.await(...)           本地 DB 落 lastPageRead
```

---

## 4. Komga 进度回写（R-3，核心增量）

`ReaderViewModel.syncKomgaBookProgress(readerChapter, pageIndex)`（约 L1490）：

```kotlin
private fun syncKomgaBookProgress(readerChapter: ReaderChapter, pageIndex: Int) {
    val url = readerChapter.chapter.url
    if (!url.startsWith(KomgaSource.BOOK_URL_PREFIX)) return        // 只处理 Komga 书
    val bookId = url.removePrefix(KomgaSource.BOOK_URL_PREFIX)       // 从 chapter.url 解析 bookId
    val pages = readerChapter.pages ?: return
    val completed = pageIndex >= pages.lastIndex
    val now = System.currentTimeMillis()
    if (!completed && now - lastKomgaBookSyncTimestamp < KOMGA_PAGE_SYNC_INTERVAL_MS) return  // 5s 节流
    lastKomgaBookSyncTimestamp = now
    viewModelScope.launchNonCancellable {
        runCatching {
            val prefs = KomgaPreferences(Injekt.get<Application>())
            KomgaApiClient(prefs.connection()).updateReadProgress(bookId, pageIndex + 1, completed)
        }
    }
}
```

要点：
- **bookId 来源**：chapter.url 的 `komga://book/{id}` 前缀，无需 track 服务。
- **节流**：`KOMGA_PAGE_SYNC_INTERVAL_MS = 5_000`（同文件 L761）；末页（completed）不受节流限制，必回写。
- **completed**：`pageIndex >= pages.lastIndex` 时 `completed=true`（Komga 端标记已读）。
- 调用 `KomgaApiClient.updateReadProgress(bookId, page, completed)` → `PATCH /api/v1/books/{id}/read-progress`。

---

## 5. 自动阅读模式（R-5）+ 设置入口

`KomgaDbBridge.applyReadingMode(manga, readingDirection)`（约 L102）：

```kotlin
val mode = when (readingDirection) {
    "LEFT_TO_RIGHT" -> ReadingMode.LEFT_TO_RIGHT
    "RIGHT_TO_LEFT" -> ReadingMode.RIGHT_TO_LEFT
    "VERTICAL", "WEBTOON" -> ReadingMode.WEBTOON   // Komga 垂直/条漫 = 连续滚动
    else -> return
}
val currentMode = manga.viewerFlags and ReadingMode.MASK.toLong()
if (currentMode == 0L) {                            // 仅当用户未手动设过才应用
    mangaRepository.update(MangaUpdate(id = manga.id, viewerFlags = mode.flagValue.toLong()))
}
```

- 保护用户手动覆盖：`viewerFlags & MASK == 0` 才写。
- 修过的坑：Komga 枚举含 `WEBTOON`，最初漏映射导致条漫要等第二页才生效 → 现已 `"VERTICAL","WEBTOON" -> WEBTOON`。

**设置入口（已挂进 Komiho）**：`KomgaMainActivity.SettingsTab` 末尾「阅读」分组 → "阅读设置" 行 → 全屏 `Dialog` 内 `Navigator(SettingsReaderScreen)` + `CompositionLocalProvider(LocalBackPress provides {关闭})`。直接复用 `ReaderPreferences`，原生阅读器即时生效。
（commit `cb173fe`，构建 run `32341037998` ✅）

---

## 6. 图像增强（MihonSY 自带，复用）

`ReaderPreferences.enhancementMode`：单选，0 Off /1 Anime4K（UI 隐藏）/2 Lanczos3 /3 Catmull-Rom /4 Spline36。
实现在 `MihonSyEnhancer`（native）：Anime4K 走 GLES 实时 shader；Lanczos3 等走 CPU resample（`nativeLanczosProcess` / `nativeResample`）。
约定：因视觉优于 Anime4K，设置里**隐藏 Anime4K**，默认走 Lanczos3（见全局记忆）。

---

## 7. 本仓库阅读器相关「已做 / 待做」

### ✅ 已落本仓库
- R-3：翻页实时回写 Komga 书进度（`syncKomgaBookProgress`，5s 节流，末页必写）。
- R-5：按 Komga `readingDirection` 自动阅读模式（含 WEBTOON 修复）。
- 固定签名：`komiho-debug.jks`（ci-apk Release 资产，绕 500MB artifact 配额）。
- M3 移植：`komiho`(v1) 的 komga-data 端点 + 9 个 UI 文件（排除自写 Reader）并入 V2。
- 设置入口：`SettingsReaderScreen` 挂进 Komiho `SettingsTab`（commit `cb173fe`）。
- 启动图标：新 ico 接入 main+debug（commit `1fe0f8e`，run `32341762294` ✅）。

### 🗺️ 路线规划（参考 Komelia 重写，**尚未落入本仓库**）
> 以下来自 Komiho 总体路线（全局记忆），属未来方向，本仓库当前仍是 MihonSY 阅读器原样复用。
- **S0**（磁盘缓存 + 采样解码 + 预取）：规划中/进行中，未在 V2 实装。
- **S1**（RTL / 双击 pinch zoom）：进行中，未在 V2 实装。
- **S2**（spread + 跨书 + webtoon）、**S3**（打磨）：排期后续。
- 若启动 S0–S3 重写，应以 `Snd-R/Komelia` reader 代码为架构参考，并保留上面 §4/§5 的 Komga 接入与回写逻辑。

---

## 8. 关键约定 / 坑

- **domain 包名**：本仓库用 `tachiyomi.domain.*`（非 `eu.kanade.domain.*`）。`MangaUpdate` = `tachiyomi.domain.manga.model.MangaUpdate`（含 `viewerFlags: Long?`）。
- **BOOK_URL_PREFIX**：`"komga://book/"`，bookId 从 `chapter.url` 解析（回写/取书都靠它）。
- **EMPTY**：`mihon.core.common.extensions.EMPTY` 是扩展属性，需显式 import（`KomgaDbBridge` 曾因漏 import 编译失败）。
- **末页判定**：`pageIndex >= pages.lastIndex`（不是 `== lastIndex` 的边界脆弱写法）；`hasExtraPage` 时末页为 `lastIndex-1`。
- **ReadingMode.MASK = 0x7**：`viewerFlags` 低位存阅读模式，写入前 `& MASK` 判断是否用户已设。
- **Viewer 选择**：`ReadingMode.toViewer()` → L2R/R2L/Vertical = Pager；WEBTOON/CONTINUOUS_VERTICAL = Webtoon。Komga 条漫靠 `WEBTOON` 走连续滚动，无元数据则靠比例首屏预判（前 5 页预检，第二页内切）。
- **构建**：本机无 Android SDK，全走 GitHub Actions（push 触发，`ci-apk` Release 资产）。debug 变体产物 `app-arm64-v8a-debug.apk` 即用户安装包。
