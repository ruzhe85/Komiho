# Komiho Changelog

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
