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
- **Bars** — both bars are square, one shape on every page: the top bar (`TopSheetBar`) and the bottom bar (`NavSheetView`, whose only curve is the arc round the New tab button) meet the page with a straight edge, a hairline and a soft shadow. Neither covers any of a website: the page starts below the top bar and, on a website, stops at the bottom bar's straight edge.
- **Home page scrolled** — once home's own search box scrolls away, the same box takes the top bar on its own: the owl and the gear slide away, the box keeps home's colour with no outline and home's 16dp from either edge, 44dp tall and centred in the bar with 7dp above and below as Chrome's is, and the bar fades into the page's background, its line and shadow with it (`TopSheetBar.blend`, driven by `setHomeBarProgress`). Scrolling back up brings the owl and the gear back.
- **Search mode** — laid out as Chrome's: the search field and the suggestions are two cards of the same colour (`sugSheet`), the field with no outline, on the search page's own background (`searchPageBg`; Night Owl's wash in Night Owl), and the top bar goes flat in that background (`TopSheetBar.flat` - no curve, shadow or line).
- **Bottom bar** — Home, Bookmarks, a round Search button sitting in an arc of the bar's top edge - the Search owl peeking up into a sky that follows the phone's clock (`OwlButtonView`: a blue sky, the sun and drifting clouds by day; an orange horizon with the sun sinking behind the owl from 18:15; stars, a shooting star now and then and the moon in its real phase from 19:15, and the reverse at dawn; the owl blinks, looks about and bounces when tapped; Night Owl tabs always show the night; it animates at 30fps only while on screen, 12fps in Battery Saver, and holds still when animations are off); a tap opens search in the tab on screen, exactly as a tap on the search box does, with no new tab - (`NavSheetView` draws the bar, its arc, the ring around the button and the shadow as one shape), Tabs (with the open-tab count on a badge) and Menu, icons only, with tooltips on long press; long-pressing Tabs also opens a new tab. Back, Forward and Reload are the first row of the menu, and Settings is the gear at the top right. While a page loads, a ring fills around the New tab button in a gradient of the chosen accent (`LoadRingView`); there is no line across the top of the page. The bar is a 56dp row of buttons. On the home page the page runs 28dp on under the bar, as its bottom padding and the feed's ad card allow for; on a website it stops at the bar's straight edge (`applyBarOverlap`), so nothing a site pins to the bottom of its window is hidden under the bar - only the New tab button floats over its centre. Scrolling down a page - the home page or a website - slides both bars away and gives the page the whole screen; scrolling up, reaching the top, a new page, a tab switch, search, the menu or the tab deck bring them back (Settings → Customize → Hide bars while scrolling, on by default; never with TalkBack on). The page takes the whole screen at the start of a hide, while the bars still cover its new edges, and gives it back at the end of a show, behind the bar - each time offset in the same frame so its text does not jump - and the bars slide under the status and navigation bar strips, which keep the bars' colours meanwhile (`EdgeScrim`), so no white shows during either move. Pages are never told a system bar overlaps them (no false `env(safe-area-inset-*)`), so a site's own bottom bar, such as YouTube's, sits flush against ours. A sideways swipe across the bottom bar (`SwipeBar`) moves between tabs, the page following the finger with the next tab sliding in beside it; a flick or a third of the way carries through, past the first or last tab it only gives like rubber. Gestures finish on a small damped spring (`Spring.kt`) that carries the finger's speed. The bar steps aside while typing into a page, and the page shrinks above the keyboard so the field being typed in stays visible.
- **Site icon in search** — searching from a website shows that site's own icon, crisp, at the start of the search field (`SiteIcons`): the page is asked which icons it declares (apple-touch-icon and icons with a declared size), the smallest of at least 96px is taken, with /apple-touch-icon.png and /favicon.ico on the site itself as fallbacks - no third-party icon service, so nobody but the site learns where the user is. Kept in memory and in the cache directory (memory only in Night Owl).
- **Page colours in the bars** — on websites both bars take on the colours of the page they frame, as Chrome's toolbar does: the top bar (and the status bar strip) the colours along the top of the page, the bottom bar those along its bottom (`PageTint`, sampled with PixelCopy, or read from the page's own styles when the screen cannot be read). A dark page gives dark bars with light text and icons (and light status and navigation bar icons); a white page white bars. Colours are calmed - saturation capped at 40% - so a red site gives a muted red, never a hard one, and the three thirds of each bar drift only gently from one colour. In the dark theme a light page leaves the bars dark. Not on the home page, in Night Owl or on error pages; Settings → Customize → Page colours in the bars turns it off.
- **Address bar suggestions** — besides history, bookmarks and the engine's suggestions: open tabs matching what is typed come first as "Switch to tab" (Night Owl and normal tabs are never mixed), and with the box empty a link copied in the last ten minutes is offered as "Link you copied". From Android 12 the clipboard is only read when that suggestion is picked, using the system's own note that it holds a link, so the "pasted from your clipboard" notice does not appear every time search opens.
- **Sign-in pop-ups** — "Continue with Google" style pop-ups open as a tab and return to the site when done. A pop-up that comes back from the provider and then stalls on a blank callback page, or whose `window.close()` is refused, is folded back into the tab that opened it, which is reloaded so it picks up the new session.
- **Resilience** — a crashed or reclaimed page renderer reloads the tab instead of taking the app down; a page that stops responding - which holds up every tab, since they share one renderer - gets a "wait or end it" prompt, from Android's report (Android 10+) or the app's own check that Home and New tab actually start loading; "Wait" holds it back for 10 seconds, not for good; ending it restarts the pages (Android 10+) or the browser with its tabs (older), pages that are still stuck after that are offered a restart of the browser with its tabs (`RestartActivity`), and the tab that hung - or one whose renderer died twice in 20 seconds - comes back as a "stopped" page with Try again rather than loading into the same trouble; the number of tabs kept live is sized to the phone's memory, and background tabs are frozen when Android reports memory is short. Open tabs are saved to disk (`SessionStore`) shortly after every navigation and whenever the app is left, so after a crash, a swipe from Recents or Android reclaiming the app they come back: the tab that was showing opens with its back/forward history, the others load theirs when opened. Night Owl tabs are never saved.
- **Top Stories** — world news from international, free-to-read editions (BBC News, The Guardian, NPR, Sky News, France 24, ABC News, CBS News, UN News - the "World" category, ranked first for breadth, with stories several of them run at once rising as trending) alongside a technology-and-culture feed from 22 curated publishers (The Verge, Ars Technica, WIRED, TechCrunch, TechRadar, Tom's Hardware, Android Central, 9to5Google, 9to5Mac, IGN, Eurogamer, GameSpot, NASA, ESA, Google AI, OpenAI, Variety, Pitchfork, ESPN, Condé Nast Traveler, Dezeen and more) across categories from AI and Mobile to Gaming, Space, Music, Sports, Travel and Design. One lead story leads as a large card, chosen by freshness, how many outlets carry it, source quality, image quality and category variety, so it is not always tech; the stories after it are full cards too (picture, headline, summary, site icon and a share button), every one with a picture, and "See all" opens the full list with category filters. Optional YouTube trending videos when a YouTube Data API key is configured. Persisted to disk for offline use, and refreshed when connectivity returns.
- **Tabs** — card-based tab deck with thumbnails that rises in on a spring (Bookmarks, History and Downloads open in the same deck), starting only once its list is laid out and drawn from a layer while it moves, so it opens smoothly; flick a card sideways to close its tab (a fast flick flies off, a slow drag springs back).
- **Load measurement** — every web page is timed from its own Performance timeline a moment after it finishes (`PageMetrics`): DNS, connection, TLS, time to first byte, DOMContentLoaded, load, first and largest contentful paint, long main-thread tasks, bytes transferred, scripts, images, page size and the protocol it came over. From those each page is classed light, medium or heavy. When Android reports memory getting short (but not yet low), background tabs holding heavy pages are frozen first. Measurements stay on the phone; in debug builds each page's line is in the debug log.
- **Performance & health** — Settings → Performance & health shows how Search is running on the phone, version by version (`HealthStats`): crash-free sessions, crashes, ANRs and low-memory kills (from Android's exit records on Android 11+), page-engine crashes and reclaims, stuck pages, pages loaded and failed, HTTP errors, downloads and download failures, cold and warm start-up time, average time to first byte, first and largest paint and page load, page weight counts, and memory warnings. Only counts and timings are kept, never sites; nothing is sent anywhere; "Share report" sends the numbers by the user's hand.
- **Connection states** — a page slow to arrive says what it is waiting on in a small pill over the top of the page: "Connecting to example.com…", then "Waiting for example.com…", then "Loading content…" once it starts to arrive; fast loads show nothing. If the network drops part-way, the pill says "Connection lost · Retry", and the page reloads by itself when the network is back. A tab showing the offline or "can't reach" page retries on its own when the connection returns (or when it is next opened), each tab remembering the address that failed. A load cut by a Wi-Fi/mobile switch is retried once before any error page. The menu's Reload is Stop while a page is loading.
- **Pull to refresh** — pulling a web page past its top brings a refresh disc down with rubber-band resistance; past the trigger it spins and reloads. Driven by the page's own overscroll (`BrowserWebView`), so a list scrolling inside a page never triggers it.
- **Print or save as PDF** — a menu row hands the page to Android's print screen, which offers Save as PDF on every phone; the page lays itself out for paper with its own print styles.
- **Sites that ask for a password** — a page protected by HTTP sign-in (a router, a NAS, an intranet) gets a sign-in dialog instead of a blank page.
- **Menu** — opens on a spring from the Menu button, drawn from a layer while it grows, and can be dragged down to close (`DragPanel`).
- **History & Bookmarks** — with friendly empty states.
- **Permissions by site** — Settings → Site settings lists every site that has asked for the camera and microphone or for location, with the answer it got (Allowed, Blocked, Ask). Tap a site to set each to Allow, Block or Ask every time, or to forget the site; Search Security's "Reset site permissions" still forgets them all.
- **Browsing data** — Settings → Clear browsing data opens a screen listing what Search keeps on the phone with its size: cached files, cookies, site storage (local storage, IndexedDB, service workers), history, and downloads (`BrowsingData`). Each is cleared on its own, so freeing space does not have to sign you out of every site; "Clear all browsing data" is still the last row and does exactly what the old button did. Downloads are never deleted from there: the row opens the Downloads screen.
- **In-app Downloads** — view, open, and remove downloads without leaving the app. Before a download starts, its size is checked against the free space: a file that would leave under 200MB is stopped with a "Not enough storage" message and a shortcut to storage settings; one that would take half the free space or leave under 1GB is confirmed with a warning, even when download confirmation is off. The confirmation shows the size, where it goes and the space free. A failed download reads "Failed · tap to retry" and is fetched again from its address, with the site's cookies and the same space check; a paused one says what it is waiting for (network, Wi-Fi, a retry) and DownloadManager resumes it by itself.
- **Ad blocking** — built-in, for ads on websites (off by default; the app's own AdMob card is not affected). Per-site exceptions (`AdBlockSites`): while blocking is on, the menu offers "Allow ads on this site" / "Block ads on this site" for the site on screen, judged by the page's site so everything that page loads is let through; Settings → Ad blocking lists the allowed sites, each removable.
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
