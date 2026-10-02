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
5. Feed depends on publisher RSS terms and on CDN URL shapes. The BBC's RSS terms say personal/non-commercial and Search carries ads, which is grey rather than clear - the exposure is a takedown request, not a Play strike. Separately, `resize()` in `NewsFeed.kt` rewrites the size segment of ichef.bbci.co.uk and 365dm.com image URLs; if either CDN changes its path shape the thumbnails quietly fall back to the branded placeholder.
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
- **Feed cache:** RSS is near-real-time, but the 15-min TTL is now honoured across cold starts (the cache file's mtime is the timestamp), so a change to `SOURCES` can take up to 15 minutes to show on a device that already has a cached feed. Clear app data to see it at once.

## Config / references

- **Package:** com.devbangs.search · **namespace:** com.search.browser
- **News feed:** publisher RSS front pages, no API key and no quota. Sources are the `SOURCES` list at the top of `NewsFeed.kt` - 9 editor-curated front pages across 8 regions (UK, Europe, India, Asia, Africa, Oceania, Canada, Latin America), ~200KB per refresh. Items are gated (image, 48h max age, junk link/title patterns, min headline length) then clustered by headline overlap; cluster size is the trend score and distinct regions score again on top, so a story running on three continents outranks one on two British front pages. Output is woven by region, so the twelve visible cards span the world rather than one country's desks. Replaced NewsData.io in Sept 2026, which capped at 200 credits/day across the entire install base. No Middle East source: Al Arabiya, Arab News and Times of Israel all 403; Al Jazeera and Middle East Eye carry no per-item image. Never trust a feed without checking its age span - CNN's international feed answers 200 with items 2.5-3.5 years old.
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
