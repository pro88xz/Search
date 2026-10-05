# Search — Release Quality Gate

No release ships because "it works on my phone". Every release goes through
this list, on more than one phone, before it goes to production, and then
goes out in stages. Tick each box on a copy of this list per release.

## 1. Test matrix

Run sections 3–5 on at least one phone from each row you can get. A cheap,
slow phone finds more problems than a flagship.

| Tier | Example | Why |
| --- | --- | --- |
| Low RAM (≤3 GB, or Android Go) | any budget phone | tab freezing, renderer kills, memory warnings |
| Mid-range (4–6 GB) | Samsung A-series | what most users have |
| Flagship (8 GB+, 120 Hz) | Pixel / Galaxy S | smoothness at high refresh rate |
| Oldest supported | Android 7.0 (API 24) | minSdk; no paint shadows, no exit records |

Networks: fast Wi-Fi, slow connection (Developer options → or a weak signal),
mobile data, switching Wi-Fi ↔ mobile mid-load, and airplane mode on/off.

## 2. Before testing

- [ ] `versionCode` bumped in `app/build.gradle.kts`.
- [ ] `./gradlew assembleRelease` builds (R8 / minification check).
- [ ] Install the **release** build, not debug, for sections 3–5.
- [ ] Note the previous version's numbers in **Settings → Performance &
      health** on your own phone before updating, to compare after.

## 3. Compatibility

Each must load, scroll, and work as on Chrome:

- [ ] Heavy JavaScript app: Gmail web or Google Docs (type, scroll, open menus).
- [ ] Video: YouTube — play, fullscreen, rotate, back out of fullscreen, lock
      the phone (background audio controls), Bluetooth headphones on/off.
- [ ] Shopping: a large store page (Amazon / Jumia) — images, cart, checkout page.
- [ ] Sign-in: "Continue with Google" on a site that offers it; the pop-up
      returns to the site signed in.
- [ ] File upload: attach a photo on a web form.
- [ ] Downloads: a PDF, an image (long-press → save), a file behind a sign-in.
- [ ] WebSockets / live updates: a chat or live-score site keeps updating.
- [ ] Modern CSS and IndexedDB / service workers: an installable web app
      (e.g. a PWA) loads, and loads again offline if it supports it.
- [ ] Maps: Google Maps web pans and zooms.
- [ ] AI web app: ChatGPT web — sign in, send a message, stream a reply.

## 4. Performance

- [ ] Cold start feels instant; compare **Start-up: cold** in Performance &
      health with the previous version (should not be noticeably higher).
- [ ] Scrolling a long news page and the home feed is smooth (no stutter).
- [ ] Tabs, Bookmarks and Menu open smoothly, first time and every time.
- [ ] Back, tab switch, address bar and menu respond while a heavy page is
      still loading.
- [ ] Open 5, then 10, then 20 tabs of real sites; switching stays responsive
      and the app does not close. (50 on a flagship.)
- [ ] Average **first byte / first paint / largest paint** in Performance &
      health are in line with the previous version for similar browsing.

## 5. Reliability

- [ ] **Crash recovery:** open 4–5 tabs with some back history, swipe the app
      away from Recents, reopen: the same tabs return, the one you were on
      is showing, Back works on it. Night Owl tabs do not return.
- [ ] **Renderer recovery:** open a very heavy page on a low-RAM phone or many
      tabs until a page reloads by itself — the app must stay open.
- [ ] **Network recovery:** start loading a page, turn on airplane mode — the
      offline page or "Connection lost · Retry" shows; turn it off — the page
      loads by itself. Switch Wi-Fi ↔ mobile mid-load: the page still arrives.
- [ ] **Slow network:** on a weak connection the pill shows "Connecting…",
      "Waiting for…", "Loading content…"; Menu → Stop stops it.
- [ ] **Download recovery:** start a large download, turn off the network —
      it shows "Waiting for network…" and resumes when back. Fail one
      (e.g. a broken link) — "Failed · tap to retry" retries it.
- [ ] **Storage:** a download larger than the free space is refused with "Not
      enough storage".
- [ ] **Ads:** the home-feed ad card still appears (not in Night Owl, not for
      supporters), never over a site; consent message on first launch where
      required.

## 6. Ship in stages (Play Console)

1. **Internal testing** — upload the AAB, install from the internal link,
   run sections 3–5 on the release build.
2. **Closed testing** — a small group for a few days.
3. **Production, staged rollout** — start at 5–10%.
4. **Watch** Play Console → Android vitals (crash rate, ANR rate) for 2–3 days,
   and your own **Performance & health** screen. Users can send theirs with
   **Share report**.
5. **Increase** to 25% → 50% → 100% only while crash and ANR rates stay at or
   below the previous version. **Halt the rollout** if they rise.

Changes to WebView handling, tab or session code, or anything in sections 3–5
that failed and was fixed, always go through a staged rollout.

## 7. "Why did this version get slower?"

Settings → **Performance & health** keeps each version's numbers separately
(crash-free sessions, crashes, ANRs, renderer crashes, page failures, start-up
time, page timings, memory warnings) for the last four versions. Compare the
new version's block with the previous one; ask testers to **Share report**
from theirs. Debug builds also log each page's timing line (Menu → Share
debug log, lines with `metrics`).

## Appendix: checks for the October 2026 branch (`claude/lucid-maxwell-5r0bu7`)

These were built without a phone to run them on. Check each once:

- [ ] Search mode: the grey suggestion sheet keeps the top bar's curve at both
      edges; the search pill's outline is unbroken around the mic and scan
      buttons; Night Owl tint matches.
- [ ] Loading: no line across the top of the page; a purple ring fills around
      the + button while a page loads, then fades.
- [ ] Tabs, Bookmarks, History, Downloads and the Menu rise smoothly.
- [ ] Session restore (see section 5), including a link opened from another
      app while tabs are restored.
- [ ] Connection pill, auto-retry on reconnect, Menu → Stop (section 5).
- [ ] Settings → Clear browsing data: sizes appear; each row clears only its
      own kind; "Clear all" still clears everything; Downloads row opens the
      Downloads screen.
- [ ] Download confirmation shows size, "to Downloads" and free space.
- [ ] Settings → Performance & health shows numbers after some browsing;
      Share report works.
- [ ] Settings → Site settings → Permissions by site lists sites that asked,
      and changing one takes effect on that site's next request.
- [ ] Address bar: typing an open tab's site offers "Switch to tab"; copying a
      link elsewhere then opening search offers "Link you copied".
- [ ] Downloads: a failed download retries; a paused one says why.
