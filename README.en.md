# Komiho

<div align="center">

![Komiho](.github/readme-images/app-icon.png)

**A dedicated [Komga](https://github.com/gotson/komga) manga reader**

Package `cn.ruzhe.komiho` ｜ Version 1.0.6 (7) ｜ Android 8.0+

[English](./README.en.md) | [中文](./README.md)

</div>

---

## About

Komiho is a **Komga-only manga reader** rebuilt from [MihonSY](https://github.com/jobobby04/TachiyomiSY) / [Tachiyomi](https://github.com/mihonapp/mihon). It removes the upstream multi-source / extension / tracker stack and uses a **Komga server as the single data source**, wiring MihonSY's mature reader directly to the Komga API (v1.26.3).

- Connect to a self-hosted Komga server (URL + API-Key), with multi-server switching
- Browse libraries / series / books, with reading progress written back to Komga in real time
- Reuses the full MihonSY reader (paged / webtoon / RTL / double-page / zoom / image enhancement)

> ⚠️ Komiho requires an available **Komga server** (self-hosted or otherwise); it does not provide comic content by itself.

---

## ✨ Core Features

### 1. Server Connections (multi-Komga)

- **Connection management**: connect to a self-hosted Komga server via URL + API-Key (`X-API-Key`), validated before saving.
- **Multiple servers**: add several Komga servers and switch the active one in settings; each can be edited / deleted.
- **Seamless migration**: an old single-connection config is auto-migrated to a single entry on first launch — nothing is lost.

### 2. Library Browsing

- **Library / Series / Book** three-tier model, aligned with Komga (Series = book, Book = chapter).
- Library list (multi-library), series grid (covers from Komga thumbnails), series detail (books in order).
- **Home aggregation**: Keep Reading / On Deck sections, semantically aligned with the Komga Web Dashboard.
- Top-bar library selector (DropdownMenu) to switch the current library quickly.

### 3. Reader (full MihonSY capability)

- Paged (LTR / RTL / vertical), webtoon, double-tap pinch zoom, double-page.
- Reading settings, progress memory, wheel / key paging, and the full interaction set.
- **Progress write-back**: marks `completed` at the last page; book-level + series-level
  (`/api/v2/series/{id}/read-progress/tachiyomi`) progress is throttled back, visible on the Komga Web side.

### 4. Image Enhancement (lightweight)

| Algorithm | Type | Presets |
|-----------|------|---------|
| **Lanczos3** | Classic resampling | 1.5x / 2x / 2.5x / 3x |

- Tuned for manga / webtoon line art; fast to load and low memory usage — no heavy models like waifu2x / Anime4K.
- **Where**: Settings → Reader → Image enhancement.

### 5. Search & Filter

- Global keyword search (`/api/v1/series?search=`).
- Filter by **tag / author**; on the home search the scope is all libraries, inside a library it is the current library.
- Search results reuse the library display mode (no longer a fixed flat layout).

### 6. Settings & About

- Appearance (theme / language), Reader, Server connections (multi-server manager).
- **About** page: show version, check for updates (targeting `ruzhe85/Komiho`), and a GitHub source link.

---

## 📦 Build

### GitHub Actions (recommended, already configured in this repo)

Pushing to `main` triggers the `Build Komiho V2 APK` workflow automatically; the artifact is published to the fixed `CI` release tag:

```bash
git push origin main
# Download the latest APK (arm64-v8a)
# https://github.com/ruzhe85/Komiho/releases/download/CI/komiho-ci-arm64-v8a.apk
```

- Artifact: `komiho-ci-arm64-v8a.apk` (single ABI on the CI channel for now).
- Signing: fixed debug keystore (injected via GitHub Secrets `KOMIHO_DEBUG_KEYSTORE_BASE64`, never committed).
- Dependencies: JDK 17 + Android SDK + NDK 28.2 + CMake.

### Local Build (not recommended)

```bash
# Requires JDK 17, Android SDK, NDK 28.2.13676358, Gradle 9.6.1
./gradlew assembleRelease
```

---

## 🗂️ Project Structure

| Path | Description |
|------|-------------|
| `komga-data/` | Komga API client (OkHttp + X-API-Key), DTOs, connection preferences |
| `app/src/main/java/app/mihonsy/komga/ui/` | Komiho's own UI (connect / home / series / reader / settings) |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/` | Reused MihonSY reader |
| `i18n/` | Multiplatform resources (app name `Komiho`, etc.) |
| `.github/workflows/build.yml` | GitHub Actions build configuration |

---

## 📝 Changelog

See [CHANGELOG.en.md](./CHANGELOG.en.md).

---

## ⚠️ Notes

- For personal learning and use only; do not use commercially.
- Please respect the copyright of the manga you read.
- This app requires an available Komga server; you are responsible for your server's data safety.
- This fork is not affiliated with the upstream project; for issues please open an Issue in this repository.

---

## Credits

- [MihonSY (jobobby04)](https://github.com/jobobby04/TachiyomiSY) — upstream fork base (reader capability)
- [Mihon](https://github.com/mihonapp/mihon) — main project (Tachiyomi successor)
- [Komga (gotson)](https://github.com/gotson/komga) — media server and API
