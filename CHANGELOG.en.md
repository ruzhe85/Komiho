# Komiho Changelog

## v1.3.1 (2026-09-26)

- New Image enhancement — "Force AI on large images" toggle: images with enough resolution but poor scan quality also get AI detail restoration
- New Remote cache progress animation
- Improved Enhancement badge now shows the output resolution
- Improved "Opening" hint shown while decoding to avoid a black screen with no feedback
- Fixed WebToon mode could intermittently get stuck on the loading spinner
- Fixed Image-type EPUB falls back to the file browser after opening
- Fixed Settings → Storage "remote cache" stats missed the PDF cache

## v1.3.0 (2026-09-25)

- New Local / WebDAV / SMB PDF reading
- New File browser search
- New Image enhancement — denoise
- Improved File browser now sorts by number / letter / pinyin initial
- Fixed Nubia / Red Magic failing to load NPU models

## v1.2.0 (2026-09-19)

- New AI image upscaling (GPU / NPU): base code ported from [mihon_img_upscale](https://github.com/HaoweiLi97/mihon_img_upscale)
- New Source management gains a "Startup" section to pick the default cold-start behavior
- New Same-path folder auto-load; loose-image folder continues to the next volume after finishing one
- Improved Reader performance improved, several bugs fixed
- Improved Reader paged and WebToon mode cache values are now adjustable
- Improved Image enhancement applies in real time
- Improved Komga UI reworked — downloads now work
- Improved Enhancement badge shows engine and timing accurately per page
- Fixed Full i18n (zh / zh-TW / en); all screens display correctly after switching language
- Fixed Cold start always landed on the welcome page
- Fixed SMB / CIFS browsing could intermittently fail when the OS reclaimed the idle connection
- Fixed Komga recents cover used the series cover instead of the book cover

## v1.1.0 (Komiho) (2026-09-12)

- New Local file browsing
- New WebDAV share browsing
- New SMB/CIFS share browsing
- New Local bookmarks and history
- New Source management — one place to manage every source
- New Recents, now the default start page
- Improved Komga UI redone — much nicer to use now
- Improved Manga reader performance improvements

## v1.0.6 (Komiho)

The first publicly released Komiho version, with the following capabilities:

### Server Connections
- Connect to a self-hosted Komga server via URL + API-Key (`X-API-Key`), validated before saving.
- **Multiple Komga servers**: add several servers and switch the active one in settings; each can be edited / deleted (with confirmation).
- An old single-connection config is auto-migrated to a single entry on first launch.
- Fix: the "reconfigure connection" button wrongly jumped back to the home screen; "clear connection" now clears only connection data instead of wiping display / theme / language preferences.

### Library Browsing
- Library / Series / Book three-tier model, aligned with Komga (Series = book, Book = chapter).
- Library list, series grid (covers from Komga thumbnails), series detail (books in order).
- Home aggregation of Keep Reading / On Deck, semantically aligned with the Komga Web Dashboard.
- Top-bar library selector (DropdownMenu) to switch the current library quickly.

### Reader
- Reuses the full MihonSY reader: paged (LTR / RTL / vertical), webtoon, double-tap pinch zoom, double-page, reading settings, progress memory.
- Progress write-back: marks `completed` at the last page; book-level + series-level (`/api/v2/series/{id}/read-progress/tachiyomi`) progress is throttled back.
- Fix: the reader / detail page no longer wrongly shows "Open in WebView" / "Open in browser" / "Share" (Komga uses an internal scheme with no real web page, hidden for Komga); the reader bottom-button settings also hide these three.

### Image Enhancement
- Lanczos3 (1.5x / 2x / 2.5x / 3x), lightweight with no heavy models.

### Search & Filter
- Global keyword search.
- Filter by tag / author: on the home search the scope is all libraries, inside a library it is the current library; switching tabs reloads automatically.
- Search results reuse the library display mode (no longer a fixed flat 1×3 layout).

### Settings & About
- Appearance, Reader, Server connections (multi-server manager).
- **About** page: show version, check for updates, and a GitHub source link (update checker targets `ruzhe85/Komiho`).
