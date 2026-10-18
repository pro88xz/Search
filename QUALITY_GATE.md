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
   and your own **Performance & health** screen.
5. **Increase** to 25% → 50% → 100% only while crash and ANR rates stay at or
   below the previous version. **Halt the rollout** if they rise.

Changes to WebView handling, tab or session code, or anything in sections 3–5
that failed and was fixed, always go through a staged rollout.

## 7. "Why did this version get slower?"

Settings → **Performance & health** keeps each version's numbers separately
(crash-free sessions, crashes, ANRs, renderer crashes, page failures, start-up
time, page timings, memory warnings) for the last four versions. Compare the
new version's block with the previous one; ask testers for a screenshot
of theirs. Debug builds also log each page's timing line to logcat
(`adb logcat -s Search`, lines with `metrics`).

## Appendix: checks for the October 2026 branch (`claude/lucid-maxwell-5r0bu7`)

These were built without a phone to run them on. Check each once:

- [ ] Search mode, from home and from a website, light, dark and Night Owl:
      the search field and the suggestion card are two cards of the same
      colour (white in light mode) on the grey search page, the field with no
      outline, and with a little air between it and the status bar - on a
      phone with a teardrop or hole camera nothing sits under the camera;
      the top bar is flat in that grey, with no curve, shadow or
      line; leaving search brings the bar's hairline and shadow back.
- [ ] Home page, scroll down past its search box: the same box takes the
      top on its own (same colour, no outline, 16dp from either edge, centred
      in the bar with a little air above and below, as in Chrome); the owl and the gear slide away and the top bar fades into the
      page's grey; scrolling back up brings them back. Open a story from the
      scrolled home page: the website has the owl, gear and normal address
      bar. Searching from the scrolled home page keeps the owl.
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
      in a release build there is no Ads card, no ad lines and no Share.
- [ ] Settings → Site settings → Permissions by site lists sites that asked,
      and changing one takes effect on that site's next request.
- [ ] Address bar: typing an open tab's site offers "Switch to tab"; copying a
      link elsewhere then opening search offers "Link you copied".
- [ ] Downloads: a failed download retries; a paused one says why.
- [ ] Figma: on figma.com tap Log in - the sign-in form appears (not an
      empty white box) and signing in works; Home and other tabs keep
      working throughout. Videos without a picture of their own show no grey
      play-button box before they play.
- [ ] A page that freezes the app: tapping Home or + brings up "This page
      isn't responding" within a few seconds; "Wait" brings it back 10 s
      later if still stuck; "End it" (or "Restart" on Android 9 and below)
      makes Home and the other tabs work again, and the frozen tab shows
      "This page stopped responding" with Try again. If pages are still stuck
      after "End it", "Pages still aren't loading" offers "Restart": Search
      closes and opens again by itself with its tabs, the frozen one as the
      stopped page.
- [ ] On a site with a bar pinned to the bottom (songsterr.com), nothing of it
      is hidden under the bottom bar except under the + button.
- [ ] Both bars are square on home and on websites, in light, dark and Night
      Owl; no grey wedges anywhere. On amazon.com nothing at the top of the
      page is cut (the logo and the cart are whole). The bottom bar sits
      close above Android's buttons, its icons centred in it.
- [ ] Change the accent (Settings → Customize): the loading ring takes it.
- [ ] The bottom bar's centre button shows the owl in a sky that matches the
      clock: blue sky, sun and clouds by day; orange horizon and the sun
      sinking from 18:15; stars and the moon (in tonight's phase - compare
      with the real moon) from 19:15; the reverse from 05:45. Night Owl tabs
      show the night. Tapping it bounces the owl. With animations off
      (Developer options or Remove animations) it holds still.
      Tapping it - from home, a website, find in page - opens search in the
      same tab, as tapping the search box does: no new tab, the tab count
      does not change. Back closes search and leaves the page as it was.
      Long-pressing Tabs still opens a new tab.
- [ ] Dark mode, with three-button navigation and with gestures: the bottom
      bar and the strip under Android's buttons are one colour (also with
      the tabs screen, the search sheet and the menu open, and in Night Owl);
      the buttons are light on dark and dark on light. The address field
      shows as a lighter pill on the dark top bar.
- [ ] Cold launch: the splash is the owl alone, no tile behind it - on white
      in light mode, on the app's dark background in dark mode (Android 12+
      and older).
- [ ] Ad blocking on (Settings → Ad blocking): on a site with ads, Menu →
      "Allow ads on this site" reloads it with its ads; the site appears in
      Settings → Ad blocking; "Block ads on this site" or removing it there
      blocks them again. The home-feed AdMob card is unchanged either way.
- [ ] Hide bars while scrolling: on a long article and on the home feed,
      scroll down - both bars slide away and the page fills the screen with
      no jump in the text; scroll up a little - both come back. At the top of
      the page, on opening a link, switching tabs, tapping the owl, the menu
      or the tabs, the bars are there. Typing into a page field still keeps
      the bottom bar out of the keyboard's way. Settings → Customize → Hide
      bars while scrolling off: the bars stay. With TalkBack on: the bars stay.
- [ ] Site icon: on a website (e.g. github.com, bbc.com, amazon.com) tap the
      owl - the search field shows the site's icon, sharp at 24dp, rounded,
      before the address. A site seen before shows it at once. On home: no icon.
- [ ] Page colours: on a site with a coloured header (e.g. a red or blue
      news site), the top bar and the status bar strip take a soft wash of
      it, not a solid block; a site with a dark footer tints the bottom bar.
      Scroll to a differently coloured part: the bars ease to it. Icons stay
      readable in light and dark theme. Home, Night Owl and the offline page:
      plain bars. Settings → Customize → Page colours in the bars off: plain.
- [ ] Top Stories shows World stories (BBC, Guardian, NPR, Sky, France 24,
      ABC, CBS, UN News) among the first cards, each with a sharp picture, and
      a "World" filter chip under See all. In a debug build, logcat "feed"
      lines: each world source shows the URL that answered and items kept.
      The feed's ad card is where it always was.
- [ ] Menu > Print or save as PDF on an article: Android's print screen
      opens; Save as PDF writes a readable PDF. On home it says to open a page.
- [ ] A page behind HTTP sign-in (a router's admin page, or
      https://httpbin.org/basic-auth/user/pass): a "Sign in to ..." dialog;
      right details open the page, Cancel leaves the site's own 401 page.
- [ ] (1.6.1) On a website the address box shows the site's icon once the
      page has loaded, before the address; it stays while typing a search.
- [ ] (1.6.1) On a dark site (e.g. meshy.ai) both bars turn a soft grey-green
      within a second or two of the page loading, even on mobile data.
- [ ] (1.6.1) Top Stories has no stories about killings, war, terror or other
      graphic events; Star Wars, game and tech stories still appear.
- [ ] (1.6.1) Play music or a video, leave the app: the media notification
      shows the owl silhouette (purple or white), not a white square.
- [ ] (1.6.1) Launcher icon, owl on error/offline pages and suggestion icons
      look exactly as before (they are now WebP).
- [ ] (1.6.2) Scroll down and up on YouTube, a news site and the home page:
      the bars slide away and back with no white band at the top (under the
      clock) or bottom at any moment; the status bar strip keeps the bar's
      colour while the bar is away.
- [ ] (1.6.2) m.youtube.com: YouTube's own bottom bar (Home, Shorts, You)
      sits directly on our bar, with no empty band between them.
- [ ] (1.6.2) meshy.ai (dark): both bars go dark, the address and icons white,
      the clock and battery icons white. YouTube (white): bars white, icons
      dark. A red site: a muted red, not a bright one. Dark theme on a white
      site: bars stay dark.
- [ ] (1.6.3) Scroll down on YouTube, meshy.ai and the home page and stop
      with the bars away: the page reaches the strip above Android's buttons,
      with no grey or white band between them, for as long as the bars stay
      away. YouTube's own bottom bar sits right on that strip.
- [ ] (1.6.3) Scroll back up: the bars return over the page with no gap
      opening at the bottom on the way. A page barely taller than the screen
      does not hide them at all.
- [ ] (1.6.3) Top Stories: no stories of injuries, attacks, war, conflict,
      crime, disasters or deaths, and no bloodied or violent pictures; tech,
      game, science and entertainment stories still appear.
- [ ] (1.6.3) Open home after an hour away: stories appear at once (the saved
      ones), and fresher ones replace them within seconds when the page is
      at the top. While reading further down, the stories do not change
      under you; they update once you are back at the top.
- [ ] (1.6.3) First run after Clear data: stories appear within about 3-5
      seconds, then fill out when the slower publishers answer.
- [ ] (1.6.4) Menu > History: every row is a name over a site ("cats" over
      "Google search", a page title over "youtube.com"); no row shows an
      address. Same for Bookmarks and for history rows in suggestions.
- [ ] (1.6.4) Home: no Reddit tile; Amazon is there with its icon; Via sits
      last before Add with the Via app icon (after a moment on first run, a
      "V" until then). Tapping Via opens the Play Store app on Via's listing.
      Long-press Via > Remove takes only Via away.
- [ ] (1.6.4) A dark site (movieboxpro.app, meshy.ai): no light line under the
      top bar; the address pane reads as a lighter pane of the bar.
- [ ] (1.6.5) developer.android.com and a news site: both bars are the same
      colour and it does not change while scrolling down past cards or
      pictures. A site with a theme colour (YouTube in dark mode,
      github.com) gives both bars that colour.
- [ ] (1.6.6) Open a dark site (movieboxpro.app, meshy.ai) and
      developer.android.com: both bars stay white (dark in dark theme), with
      dark icons, on every site. Settings > Customize > Page colours in the
      bars is off; turning it on brings the site colours back.
- [ ] (1.7.0) Home feed: scroll to the ad card at the end, scroll up a little so
      the bars come back, then down again: the card never shows lower than
      its place for a moment, and near the end of the feed the bars stay.
      The card still loads, shows "Ad" and opens on tap as before.
- [ ] (1.7.0) Settings and every section (Security, Customize, Site settings,
      Accessibility, Browsing data, Performance & health, Ad blocking): the
      same light grey page as home and search (no lavender), white cards
      under accent labels, in light and dark. Change the accent: switches,
      radio buttons, icons, labels and dialog buttons follow it. Night Owl:
      the bars keep their purple wash.
- [ ] (1.7.0) Page colours in the bars on: on a dark site (meshy.ai,
      movieboxpro.app) the address box is a visible lighter pane with white
      text; on a coloured light site it is a shade darker than the bar. It
      eases with the bar when the colour changes.
- [ ] (1.7.0) Menu > Share, Translate page (and Show original on the
      translated page), Reader view (on a news article; again to go back;
      on home it says to open a page), Add to Home screen (the icon opens the
      page in Search).
- [ ] (1.7.0) Tabs: close one in the deck - "Tab closed · Undo" brings it
      back in its place with Back still working; Menu > Reopen closed tab
      later does the same. Not offered in Night Owl.
- [ ] (1.7.0) Settings > Make Search your default browser: Android's own
      dialog (Android 10+) or Default apps; the row goes once Search is the
      default. On the fourth launch the one-time offer appears once.
- [ ] (1.7.0) Downloads: a multi-GB file (a Linux ISO) downloads with
      "45% · x of y GB · z MB/s" in the list, survives Wi-Fi off/on, and the
      app stays smooth. A web app export of a few hundred MB (a blob: link)
      saves with a progress notification and lands in Downloads.
- [ ] (1.7.0) Search suggestions off (Security): typing shows history and
      bookmarks only. Dark websites on (dark theme, Android 13+): a white
      site is darkened. Force enable zoom on: a site that blocks pinch-zoom
      zooms.
- [ ] (1.8.0) Feed ad: Settings > Performance & health > Feed ad shows
      "asked N, filled N, failed N, on screen N". With airplane mode on at
      launch, then off, a card arrives within a few seconds (and on the next
      return to the app if it was in the background). Leave the app for over
      an hour and come back to home: a new card replaces the old one. No
      request in Night Owl, none for supporters ("Supporter: yes" shows).
- [ ] (1.8.0) The owl: on a cold start it is asleep low in its window as the
      splash leaves, then rises, wakes (groggy by day, at once at night),
      looks at you, tilts and winks, once - not on rotation. With the consent
      form up it waits (or just rises after 15 s). Back after 20 minutes: drowsy
      eyes open wide; after 3-20 minutes: a slow blink and nod; after Settings:
      nothing. Blinks look natural, eyes dart and settle, the head follows.
      Press: it squashes with a click; release: a hop and a light tick; a
      sideways swipe starting on it leaves no squash behind; long press: it
      fluffs up and the "Search" tooltip shows. Loading: the eyes follow the
      ring round; done: a slow blink and nod; switch to a loaded tab mid-load:
      the eyes stop following. Same motion on a 60Hz and a 120Hz phone. With
      animations off it holds still, looking at you. Touch feedback off
      (system) or Vibration on tap off: no vibration at all on the bar.
- [ ] (1.8.0) Haptics: Home, Bookmarks, Tabs, Menu and New tab tick and
      spring under the finger; pull to refresh, closing a tab and a tab
      swipe confirm. Settings > Customize > Vibration on tap off: none.
- [ ] (1.8.0) Long-press the app icon: New tab, Night Owl tab, Voice search,
      Scan QR each open straight into that. Settings > Add the Search widget
      pins it; each of its parts opens the right thing. Quick Settings: the
      Night Owl tile opens a Night Owl tab (Android 14+ too).
- [ ] (1.8.0) Select text in another app (Messages, Gmail): "Search" is in
      the menu and opens the results in Search; Back returns to that app.
- [ ] (1.8.0) Home: "Jump back in" shows the page last read in another tab
      and a tap returns to it with Back still working. The blocked line
      shows this week's count while ad blocking is on. Leave for 4 hours
      (or set the clock forward): the app opens on home, old tab intact.
- [ ] (1.8.0) Long-press a link: Open in new tab stays on the page with
      "Switch"; Open in Night Owl tab; Copy link text; Download link. An
      image: Search image with Google Lens. Hold Back or Forward in the
      menu: the tab's pages that way, and picking one goes there.
- [ ] (1.8.0) Tap the site icon in the address box: page info (secure or
      not, certificate issuer, permissions with Reset, Block/Allow ads).
- [ ] (1.8.0) Tab deck: type in the filter to narrow the cards; drag a card
      to reorder (order kept after a restart); overflow: Close all, Close
      others, Close duplicate tabs (n), Reopen closed tab, Delete last 15
      minutes (history from the last 15 minutes is gone).
- [ ] (1.8.0) Menu > Zoom on a site: the page grows and shrinks live; open
      another site (normal size), come back (kept). Settings > Accessibility
      lists it; tapping it there returns it to normal. In Night Owl the
      size is not kept.
- [ ] (2.0.0) Explore looks and behaves exactly like 1.9.x home (stories,
      feed ad, shortcuts, Jump back in, blocked line). Upgrading a phone on
      1.9.x: it opens on Explore and "Search has 3 modes now" appears once,
      over home, not over the consent message or while typing; "Got it"
      closes it, "Choose mode" opens Settings > Browsing experience.
- [ ] (2.0.0) Fresh install: after the splash (and the consent message
      where one applies) the owl's introduction opens - the owl rises and
      waves hello, the three cards can be tapped at once, the button reads
      "Use Focus" etc.; Skip and Back keep Explore; it never shows again.
      Not shown when the first open is a link from another app.
- [ ] (2.0.0) Settings > Appearance & browsing > Browsing experience: the
      row shows the mode in use; a tap on a card applies it with "Browsing
      experience changed to Focus." and home changes on return, with tabs,
      bookmarks, shortcuts and history untouched. Focus: search, one row of
      shortcuts, Jump back in, no stories and no feed ad. Zen: the search
      box alone (Shortcuts in Zen on: four icons under it). Survives a
      force-stop and relaunch, rotation and dark mode; TalkBack reads each
      card as a radio button, checked or not.
- [ ] (1.9.0) Home: the stories are one white card with rounded corners;
      every story has its full-width picture, headline and publisher row
      (icon, name, age, Share, ⋮); the featured story leads it with "Top
      story"; YouTube stories show a play button and "Channel · YouTube".
      ⋮ > Hide stories from X: they go, Undo brings them back; Not
      interested: that story goes and stays gone after a restart.
- [ ] (1.9.0) Feed ad: after the second story under the featured one, the
      ad appears as one more story on the card (picture, headline, icon,
      "Ad", call to action), lined up exactly with the card's edges, and it
      scrolls with the page with no lag or jump, in light and dark, with
      the bars sliding away and back, after See all and a category filter,
      and after rotating. With the feed offline (no stories) it sits at the
      end of the page. Taps on its button open the ad; a tap that stops a
      fling does not.
- [ ] (2.0.6) Feed: the first ad after the second story exactly as before.
      Scroll on: one more ad (test ads in debug) after the 11th, 19th,
      27th and 35th story, each appearing in its place without the page
      jumping, scrolling with the page, taps on its button working and a
      tap that stops a fling not counting. None in Night Owl, for
      supporters, in Focus/Zen or under a category chip.
- [ ] (2.0.6) Offline, open a site, then Back: the page before (or home),
      not the offline page again. Same for an address that does not exist
      and for "not secure" > Go back.
- [ ] (2.0.5) App open ad on a launch, including the card-style creatives
      with "Continue to app": behind the ad is the owl splash, never home;
      home appears only when the ad is closed. The same coming back from
      the background.
- [ ] (2.0.5) Menu: each group's actions are chips on one line (icon and
      name), the strip swipes sideways.
- [ ] (2.0.4) Outside Europe (a non-EU SIM, consent never shown): fresh
      install, finish the intro, relaunch - the app open ad and the feed
      ad come with no consent message and no extra wait. In the EEA or UK
      the consent message still comes first and no ad before it.
- [ ] (2.0.4) Menu: Back / Forward / Reload and the hairline on top, six
      rows under them; each group opens a strip that swipes sideways and
      closes the others; every action (Home/Close Search, Reopen, Find,
      Zoom, Reader, Translate, Desktop, ads on this site, History,
      Downloads, Share, Add to Home screen, Print, Games, Coffee) works.
- [ ] (2.0.4) Home feed: videos from YouTube (play button, channel name,
      "YouTube") one in every three cards after the ad; Music, Comedy,
      Viral and Trending chips under See all; a video opens on YouTube.
- [ ] (2.0.4) Home feed opens with 12 stories; scrolling to the end adds
      more without a button until "You're all caught up"; the ad stays
      after the second story.
- [ ] (2.0.3) App open ad on the FIRST launch after installing the
      update (not a fresh install) and after force-stopping the app: the
      splash stays until the ad is on screen, then home. Repeat with the
      phone's location in the EEA (consent already answered) and outside.
- [ ] (2.0.3) Widget: the pill is a fixed height in the middle of its
      cell with a large owl; resize it, on a dense home-screen grid it
      switches to the compact size without clipping. Tapping it grows the
      browser out of the pill (Android 12+) and search is open at once.
- [ ] (2.0.3) Download an .apk and open it from Downloads: the owl dialog
      asks to allow installs, Open settings goes to Install unknown apps,
      turning it on and coming back opens Android's installer; with it
      already on, the installer opens straight away. No crash either way.
- [ ] (2.0.3) Visit YouTube and a site from the feed, go home: their tiles
      show a sharp icon, never a blurry one or a letter (a letter only for a
      site with no icon at all).
- [ ] (2.0.2) Fresh install: splash, then straight into the owl's
      introduction - home is not seen first; after it, home, then the
      consent message where one applies. Upgrade: "Search has 3 modes now"
      is the owl dialog (owl, three mode pills, accent buttons).
- [ ] (2.0.1) App open ad: launch from the launcher (not the first launch):
      the splash stays until the ad has loaded, the ad shows over the
      splash, and closing it shows home - never home first and the ad over
      it. Leave with Home and come back: the owl splash picture, then the
      ad, then home. With no connection the browser opens normally.
- [ ] (1.9.0) App open ad: open from the launcher a few times (leave with
      Home, come back): the ad shows as it opens (a test ad in debug).
      Not after sharing a link and coming back, not when a link from
      another app opened the browser, not in Night Owl. Debug build only:
      Settings > Performance & health > Ads: "App open ad: asked, loaded,
      failed, shown" counts move; a release build shows no ad numbers.
- [ ] (1.8.0) Open a link from another app, then Back: the tab closes and
      that app comes back.
