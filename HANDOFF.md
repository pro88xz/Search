# Search — Handoff / Continuity Notes

Internal working notes. Not user-facing. Keep updated as state changes.

## Current state (as of versionCode 10)

- **Latest version:** 1.0.0, versionCode 10. Code committed + pushed to `origin/main`.
- **Shipped to Play:** versionCode 8 and 9 were uploaded to production. v10 is the current build — verify whether its AAB has been uploaded/is in review.
- **versionName** has stayed "1.0.0" across versionCode 7–10. Consider bumping versionName (e.g. 1.1) for a user-visible version at some point (optional).

## What's in versionCode 10 (this batch)

- Offline-resilient news feed: `NewsFeed.kt` persists the feed to disk (`filesDir/feed_cache.json`); offline/cold-start shows last-loaded stories.
- Feed image placeholder: `home.html` preloads feed images; on failure (offline) shows a clean branded placeholder (`.thumb-ph`) instead of a broken/blank box.
- Connectivity auto-refresh: `MainActivity` registers a `NetworkCallback` (onResume) / unregisters (onPause); on offline→online it calls `window.__reloadFeedOnline()` to fetch fresh stories.
- Engine icons switched from Google's favicon service to DuckDuckGo's (`icons.duckduckgo.com/ip3/<domain>.ico`) — crisper. NOTE: Bing returns a generic 308-byte icon from all favicon services (no clean logo available); it shows a magnifying glass. Accepted as-is.
- Earlier in the same version line: design tokens (`dimens.xml`), in-app Downloads screen + empty states (History/Bookmarks/Downloads), edge-to-edge fix (androidx.activity 1.10.1), more search engines, feed source favicons, home-page polish, feed quality filter.

## Pending / open items

### Play Console (BLOCKING review — address before v10 releases)
1. **Data safety declaration — email not declared.** Play's scanner flagged the app transmits an email address (almost certainly from WebView site sign-in flows, e.g. the Facebook shortcut, not our own collection). Fix: declare Email in App content → Data safety as Collected, ephemeral, required, purpose = App functionality. Keep consistent with privacy policy.
2. **Login-wall flag (couldn't access app).** Reviewer/bot hit Facebook's login (via the Facebook home tile) and read it as our login wall. Fix: Play Console → Sign in details → state the app has no login; the Facebook screen is a third-party site from an optional shortcut; reviewers can use the address bar/search freely. (Wording drafted; <500 chars.)

### Product / revenue
3. **GamePix "Publish it!" question — UNRESOLVED.** The games section (on ToolsePulse) uses GamePix's JSON feed with sid=7R771. GamePix's dashboard also has a per-game "Publish it!" flow. Unconfirmed whether the feed integration alone monetizes or if "Publish it!" is also required. ACTION: ask GamePix Support directly.
4. **Traffic/distribution.** The built features (games revenue engine, browser) need users. Highest-leverage next work is distribution, not more features.

### Tech debt / minor
5. Feed depends on publisher RSS terms and on CDN URL shapes. Publishers' RSS terms vary (many say personal use) and Search carries ads, which is grey rather than clear - the exposure is a takedown request, not a Play strike. `NewsFeed.kt` upgrades image sizes only where a size already sits in the URL (WordPress w/h/resize, Future plc -NNN-80, Condé Nast w_NNN, IGN width=, ESPN combiner, GameSpot); if a CDN changes its shape, that source's pictures come back small and its stories quietly drop out of the feed, because only stories with a picture that holds up full width are shown.
6. Design-token migration is only partial (toolbar + new components use tokens; older screens still have hard-coded values). Approach: use tokens for new work; retrofit opportunistically, not as a big migration.
7. Bing engine icon is a generic magnifying glass (no clean logo from favicon services). Only a bundled local asset would fix it (trademark-grey); left as-is.

## Important gotchas (learned the hard way)

- **Two repos, two terminals.** Search is `/workspaces/Search`; ToolsePulse is a separate repo/Codespace. Commands must run in the right terminal — a failed `cd` can misplace files. Always prefix with `cd /workspaces/<repo> &&`.
- **Terminal auto-linkifies URLs on paste.** Pasting a bare domain like a URL can get mangled into markdown link syntax, corrupting files (this broke Settings.kt once). When editing code that contains URLs, build the URL from string parts in code, or avoid pasting raw URLs.
- **github.dev can't build.** Must use a full Codespace from github.com.
- **Codespaces default JDK crashes the Kotlin compiler.** The devcontainer pins JDK 17 — use a fresh full Codespace, not github.dev.
- **XML comments can't contain `--`.** (Broke dimens.xml once — no `----` dividers in comments.)
- **Push auth (pro88xz):** if plain `git push` prompts, use a fresh terminal + a token credential helper with `$GITHUB_TOKEN`.
- **Never navigate from inside a WebView callback.** `loadUrl()` called from `shouldOverrideUrlLoading` (or `onReceivedError`) aborts the app with SIGTRAP when the navigation is a new window's first load, because Chromium runs that callback inside its own, non-re-entrant navigation start. Use `loadAfterCallback()`, which posts the load.
- **`onRenderProcessGone` must return true.** Not handling it means WebView takes the app down whenever its renderer crashes or Android kills it for memory (SIGTRAP in `libwebviewchromium.so`, no app frame). The dead WebView can only be removed and destroyed; `replaceDeadWebView()` does that and reloads the tab.
- **CookieManager blocks.** `flush()`, `getCookie()` and `setCookie()` wait on Chromium's cookie thread, which shows up in Play as a "native lock contention" ANR. They run on `cookieIo`, never on the main thread. The same goes for drawing a WebView into a software `Canvas`: thumbnails come from PixelCopy only.
- **Debug log (debug builds only).** `DebugLog` records tab and pop-up events, page loads, main-frame errors, page console warnings/errors, renderer loss and hangs, with URL query values and fragments stripped. Menu → "Share debug log" sends it with the app, Android and WebView versions. It is the way to see what a site actually did on the owner's phone.
- **Feed cache:** the 30-minute TTL is honoured across cold starts (the cache file's mtime is the timestamp), so a change to `SOURCES` can take up to 30 minutes to show on a device that already has a cached feed; a cache in the old four-field shape is refreshed at once when online. Clear app data to see a change immediately - that also resets each source's remembered feed URL and dead-URL marks (SharedPreferences "news_feed").
- **Never rename a feed `Source.id`:** it keys the remembered URL for that source.

## Config / references

- **Package:** com.devbangs.search · **namespace:** com.search.browser
- **News feed (Top Stories):** publisher RSS/Atom, no API key. `SOURCES` at the top of `NewsFeed.kt` lists 22 curated publishers by category, each with an ordered list of candidate feed URLs; the first that answers with a parseable feed carrying images is remembered, and a URL answering 4xx or a web page is skipped for 24h. The candidate URLs were chosen offline (news sites are unreachable from the build container), so the first on-device check is the debug log: Menu > Share debug log, "feed" lines - each source shows the URLs it tried, their status, and items parsed and kept. Items are gated (age per source, junk link/title patterns, headline length, logo-as-image), clustered across outlets by headline overlap, scored by freshness (8h half-life), number of outlets, source tier, image quality, summary and category, and woven so no category takes more than 3 and no publisher more than 2 of the first 12. Every story shown has a picture that holds up full width (stories without one, or with only a small one, are left out); the lead is the best of them with a summary, any category, and steps aside after 4h. On the page every story is a full card (picture, headline, summary, site icon and name, time, share button); a story whose picture fails to load is removed and the next one takes its place. A refresh that brings back nothing usable keeps the feed already shown and is not tried again for a minute; a thin one (under `MIN_FRESH` stories) keeps a feed younger than six hours and waits five minutes. Output follows the v2 contract in `NewsFeed.kt` ({title, link, image, source, domain, category, summary, published, lead}); `home.html` still renders the old four-field cache. Roughly 300-600KB per refresh with gzip, every 30 minutes at most. Optional YouTube trending: `youtube.apiKey=<key>` in local.properties becomes `BuildConfig.YOUTUBE_API_KEY`; empty means no request. The key must be restricted to the YouTube Data API and to the app (package plus the SHA-1 of both the upload key and the Play App Signing key); quota is 10,000 units a day shared by every install, and each phone caches for 3h.
- **Engine favicons:** `https://icons.duckduckgo.com/ip3/<domain>.ico`
- **GamePix:** property/sid `7R771`, feed `https://feeds.gamepix.com/v2/json?sid=7R771`, games open at `https://toolsepulse.co/games`.
- **Release signing:** `keystore.properties` + `search-release.keystore` (alias `search`), git-ignored.
- **ProGuard:** `proguard-rules.pro` keeps all `@JavascriptInterface` methods + `com.search.browser.**` (critical for the JS bridge under R8).

## Build & ship checklist

1. Bump `versionCode` in `app/build.gradle.kts` (must exceed last uploaded).
2. `./gradlew assembleRelease` — R8 smoke test (catches minification breaks).
3. Install & smoke-test JS-bridge features (feed, share, games, QR scanner, downloads).
4. `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
5. Upload to Play production; write "What's new"; confirm Data Safety + Sign-in details are current.
