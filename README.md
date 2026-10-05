# Search — Web Browser

A fast, clean, privacy-minded Android web browser. Built by MEBS (Mohameds Engineering and Build Studio).

- **Package:** `com.devbangs.search`
- **Current version:** see `versionCode` / `versionName` in `app/build.gradle.kts`
- **Min SDK:** 24 (Android 7.0) · **Target/Compile SDK:** 36

---

## What it is

Search is a lightweight WebView-based browser focused on a clean home page, built-in ad blocking, and a fast, uncluttered experience. It pairs a native Android shell (toolbar, tabs, settings, downloads) with a web-based home page (`home.html`) that hosts the omnibox, quick-access tiles, and a news feed.

## Features

- **Clean home page** — omnibox search, quick-access tiles (YouTube, Facebook, ChatGPT, Wikipedia), voice search, and QR scanning.
- **Search mode** — the top of the screen turns to a white band (dark grey at night) and the grey sheet of suggestions curves up into it (`search_sheet_bg`). The field's outline is drawn over everything inside the pill, so the mic, scan and clear buttons can never cut its curve.
- **Bottom bar** — Home, Bookmarks, a round New tab button sitting in an arc of the bar's top edge (`NavSheetView` draws the bar, its arc, the ring around the button and the shadow as one shape), Tabs (with the open-tab count on a badge) and Menu, icons only, with tooltips on long press; long-pressing Tabs also opens a new tab. Back, Forward and Reload are the first row of the menu, and Settings is the gear at the top right. While a page loads, a ring in the owl's purples fills around the New tab button (`LoadRingView`); there is no line across the top of the page. Both bars stay on screen while scrolling. A sideways swipe across the bottom bar (`SwipeBar`) moves between tabs, the page following the finger with the next tab sliding in beside it; a flick or a third of the way carries through, past the first or last tab it only gives like rubber. Gestures finish on a small damped spring (`Spring.kt`) that carries the finger's speed. The bar steps aside while typing into a page, and the page shrinks above the keyboard so the field being typed in stays visible.
- **Sign-in pop-ups** — "Continue with Google" style pop-ups open as a tab and return to the site when done. A pop-up that comes back from the provider and then stalls on a blank callback page, or whose `window.close()` is refused, is folded back into the tab that opened it, which is reloaded so it picks up the new session.
- **Resilience** — a crashed or reclaimed page renderer reloads the tab instead of taking the app down; a page that stops responding gets a "wait or reload" prompt (Android 10+); the number of tabs kept live is sized to the phone's memory, and background tabs are frozen when Android reports memory is short.
- **Top Stories** — a technology-and-culture feed from 22 curated publishers (The Verge, Ars Technica, WIRED, TechCrunch, TechRadar, Tom's Hardware, Android Central, 9to5Google, 9to5Mac, IGN, Eurogamer, GameSpot, NASA, ESA, Google AI, OpenAI, Variety, Pitchfork, ESPN, Condé Nast Traveler, Dezeen and more) across categories from AI and Mobile to Gaming, Space, Music, Sports, Travel and Design. One lead story leads as a large card, chosen by freshness, how many outlets carry it, source quality, image quality and category variety, so it is not always tech; the stories after it are full cards too (picture, headline, summary, site icon and a share button), every one with a picture, and "See all" opens the full list with category filters. Optional YouTube trending videos when a YouTube Data API key is configured. Persisted to disk for offline use, and refreshed when connectivity returns.
- **Tabs** — card-based tab deck with thumbnails that rises in on a spring (Bookmarks, History and Downloads open in the same deck), starting only once its list is laid out and drawn from a layer while it moves, so it opens smoothly; flick a card sideways to close its tab (a fast flick flies off, a slow drag springs back).
- **Pull to refresh** — pulling a web page past its top brings a refresh disc down with rubber-band resistance; past the trigger it spins and reloads. Driven by the page's own overscroll (`BrowserWebView`), so a list scrolling inside a page never triggers it.
- **Menu** — opens on a spring from the Menu button, drawn from a layer while it grows, and can be dragged down to close (`DragPanel`).
- **History & Bookmarks** — with friendly empty states.
- **In-app Downloads** — view, open, and remove downloads without leaving the app.
- **Ad blocking** — built-in.
- **Night Owl** — private browsing mode with a dedicated empty state.
- **Multiple search engines** — Google, DuckDuckGo, Bing, Yahoo, Ecosia, Brave, Startpage, Yandex (with icons).
- **Media controls** — background media detection and playback controls.
- **Games** — a one-time welcome, then opens the web games section.
- **Ads and consent** — one native AdMob card at the end of the home feed, never over a site, never in Night Owl, and none for supporters. At launch Google's User Messaging Platform (`AdConsent.kt`) shows the AdMob privacy message wherever one applies (GDPR for the EEA, UK and Switzerland; the US states message), and no ad is requested until it says ads may be. Users in those regions get Settings → Ad privacy choices, Google's form to change or withdraw consent or opt out of sale or sharing; everyone else never sees the row. Debug builds request Google's native test unit, never the live one.
- **Support/Plus** — in-app purchase to support development (Play Billing).
- **In-app updates** — via Play App Update.
- **Material 3 settings** — grouped, card-based settings.

## Tech stack

- **Language:** Kotlin
- **UI:** Android Views + XML layouts (native shell) + HTML/CSS/JS (`home.html`)
- **Rendering:** Android WebView (`androidx.webkit`)
- **Build:** Gradle (Kotlin DSL), AGP, R8 minification for release
- **Key libraries:** AppCompat, Material, ConstraintLayout, RecyclerView, Activity-KTX 1.10.1, Play Billing 8.0.0, Play App Update 2.1.0, Google Mobile Ads 25.4.0, User Messaging Platform 3.1.0, Play Services Code Scanner (QR), Media, Core Splashscreen.

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

Proprietary — © 2026 MEBS. All rights reserved.
