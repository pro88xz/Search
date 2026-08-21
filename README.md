# Search — Web Browser

A fast, clean, privacy-minded Android web browser. Built by Dev_Bangs.

- **Package:** `com.devbangs.search`
- **Current version:** 1.0.0 (versionCode 10)
- **Min SDK:** 24 (Android 7.0) · **Target/Compile SDK:** 36

---

## What it is

Search is a lightweight WebView-based browser focused on a clean home page, built-in ad blocking, and a fast, uncluttered experience. It pairs a native Android shell (toolbar, tabs, settings, downloads) with a web-based home page (`home.html`) that hosts the omnibox, quick-access tiles, and a news feed.

## Features

- **Clean home page** — omnibox search, quick-access tiles (YouTube, Facebook, ChatGPT, Wikipedia), voice search, and QR scanning.
- **Discover-style news feed** — global-interest stories (world, technology, science, business) from top-tier sources via NewsData.io, with source favicons, share buttons, and offline resilience (persisted to disk; auto-refreshes when connectivity returns).
- **Tabs** — card-based tab deck with thumbnails.
- **History & Bookmarks** — with friendly empty states.
- **In-app Downloads** — view, open, and remove downloads without leaving the app.
- **Ad blocking** — built-in.
- **Night Owl** — private browsing mode with a dedicated empty state.
- **Multiple search engines** — Google, DuckDuckGo, Bing, Yahoo, Ecosia, Brave, Startpage, Yandex (with icons).
- **Media controls** — background media detection and playback controls.
- **Games** — a one-time welcome, then opens the web games section.
- **Support/Plus** — in-app purchase to support development (Play Billing).
- **In-app updates** — via Play App Update.
- **Material 3 settings** — grouped, card-based settings.

## Tech stack

- **Language:** Kotlin
- **UI:** Android Views + XML layouts (native shell) + HTML/CSS/JS (`home.html`)
- **Rendering:** Android WebView (`androidx.webkit`)
- **Build:** Gradle (Kotlin DSL), AGP, R8 minification for release
- **Key libraries:** AppCompat, Material, ConstraintLayout, RecyclerView, Activity-KTX 1.10.1, Play Billing 8.0.0, Play App Update 2.1.0, Play Services Code Scanner (QR), Media, Core Splashscreen.

## Design tokens

`res/values/dimens.xml` is the single source of truth for spacing (4dp grid), corner radius, text sizes, and icon sizes. New UI should reference these tokens (`@dimen/space_md`, `@dimen/radius_lg`, `@dimen/text_body_lg`) instead of hard-coded values.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` — browsing + connectivity detection
- `POST_NOTIFICATIONS` — download/update notifications
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — media controls
- `WRITE_EXTERNAL_STORAGE` (legacy, capped) — downloads

## Building

### In GitHub Codespaces (recommended)
The repo includes a devcontainer that provisions JDK 17 and the Android SDK. Open in a **full Codespace** (github.com → Code → Codespaces) — the browser-based github.dev editor cannot build. The devcontainer runs `.devcontainer/setup-android.sh`.

### Build commands
./gradlew assembleDebug # Debug APK
./gradlew assembleRelease # Release APK (R8 minified) — smoke test
./gradlew bundleRelease # Release AAB for Play
### Release signing
Release builds are signed via a git-ignored `keystore.properties` + `search-release.keystore` (alias `search`). Not in the repo (`.gitignore` covers `*.jks`, `*.keystore`, `keystore.properties`, `local.properties`).

## Release / distribution

- Distributed via **Google Play** (production).
- Each Play upload needs a unique, incrementing `versionCode`.
- Release notes and Data Safety declaration must stay consistent with actual behavior.

## License

Proprietary — © Dev_Bangs. All rights reserved.
