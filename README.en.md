# Komiho

<div align="center">

![Komiho](.github/readme-images/app-icon.png)

**A full-featured Android manga reader**
Supports **Komga, local files, WebDAV, SMB** and more comic sources

Package `cn.ruzhe.komiho` ｜ Version 1.1.0 (8) ｜ Android 8.0+

[English](./README.en.md) | [中文](./README.md)

</div>

---

## ✨ Features

### 📚 Multi-source reading

Komiho unifies comics from different sources into a single browsing and reading experience:

* **Komga** — Connect to a self-hosted Komga server; browse libraries, series and books, with multi-server and multi-library support and reading-progress sync.
* **Local files** — Browse and read comic files directly on your device.
* **WebDAV** — Connect to WebDAV services (e.g. a NAS) and read remote comics online without downloading the whole book.
* **SMB / CIFS** — Access shared folders on your LAN and read networked comics directly.

### 📦 Supported formats

Format support differs slightly by source:

* **Local / WebDAV / SMB direct reading**: comic archives `CBZ` `CBR` `CB7` `CBT` `ZIP` `RAR` `7Z` `TAR`, `EPUB`, and **loose-image folders** (just drop images into a directory).
* **Via a Komga server**: Komga does the page conversion server-side, so any format Komga supports (including `PDF`) works — the client reads the converted image pages.

> 💡 Local / WebDAV / SMB direct reading does not yet support `PDF`; read PDF through a Komga server (Komga converts it to image pages).

### 📖 Full reading experience

Built on the MihonSY reader, and continuously optimized for real reading:

* Horizontal paging / vertical paging / webtoon
* LTR / RTL
* Single page / double page
* Zoom and gesture control
* Smooth tap-to-page and animation
* Adjustable reading animation and scroll behavior
* Reading progress memory
* History and bookmarks

### 🕘 History & bookmarks

Full-featured history, reading progress and bookmarks, so you can quickly resume reading, find recently read items, and save important pages.

### 🖼️ Image enhancement

Built-in image enhancement provides:

**1.5× / 2× / 2.5× / 3×**

Tuned for manga line art, balancing quality, speed and memory usage.

### 🖥️ Tablet & large screen

Adaptive layouts for phones, tablets and large screens, with navigation that can sit at the bottom, left or right.

---

## ⚠️ Notes

The Komga feature requires connecting to your own **Komga server**.
Komiho does not provide any comic content; please respect copyright.

---

## 📝 Changelog

See [CHANGELOG.en.md](./CHANGELOG.en.md) ([中文](./CHANGELOG.md)).
