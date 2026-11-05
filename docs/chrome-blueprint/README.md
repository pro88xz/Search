# Chrome blueprint — what Chrome does, where Search stands, what we build

Chrome for Android is built by a large team. Search is built by one person. This folder is the
map between the two: what Chrome actually does (taken from Chromium's source, not from
screenshots), what Search already does, and the gaps we close.

## Ground rule: behaviour, not looks

**We do not copy Chrome's visuals.** Search keeps its own identity (the owl, the accents, the
wallpapers, the square bars, the arc around New tab). What we take from Chrome is:

- **What it does**: features, settings, edge cases, defaults.
- **How it behaves**: timing, ordering, what happens on each tap.
- **How fast and safe it is**: loading, memory, security, privacy.
- **The discipline of a design system**: every size, colour and duration named once and
  reused. Chrome's numbers are used only as *ergonomic floors* (minimum touch targets, readable
  text sizes, motion that is quick enough), never as a style to imitate.

## Sources

| Source | What it gave | How to get it again |
| --- | --- | --- |
| Chromium source, `main` @ `929cf21e` (2026-11-04), sparse checkout of the Android UI, `components/browser_ui`, settings, flags, safety, search engines | 1,518 resource tokens, 8,637 UI strings, 96 settings screens, 527 Android feature flags (329 with defaults) | `git clone --depth 1 --filter=blob:none --sparse https://github.com/chromium/chromium`, then `python3 tools/chrome-blueprint/extract_chrome.py <checkout> <out>` |
| Chromium for Android build 1735753 (`ChromePublic.apk` 159.0.8144.0, `SystemWebView.apk`), from `commondatastorage.googleapis.com/chromium-browser-snapshots/Android/` | Compiled facts not in the checkout: every built-in search engine's suggestion URL (`libchrome.so` strings) | REA (`npm i -g rea-agents`) with the JADX bridge for Java; `strings` for the native library |

`chrome-settings-tree.txt` is Chrome's whole settings tree as extracted, with the English labels.

Source paths below are relative to the Chromium checkout.

## Design system

Search already has a token file (`app/src/main/res/values/dimens.xml`): a 4dp spacing scale,
a corner-radius scale, a type scale, icon sizes and a 48dp touch target. HANDOFF item 6 notes the
migration is partial. What the system was missing, and is now added as tokens:

- **Motion durations** (`res/values/integers.xml`, `motion_*`). Until now every animation picked
  its own number. Chrome's timings are the reference for *how quick* each kind of move should be,
  not for how it looks:
  - **`motion_quick` 100ms**: small feedback, e.g. button swaps. Chrome uses 100ms for toolbar buttons on focus (`ToolbarPhone.URL_FOCUS_TOOLBAR_BUTTONS_DURATION_MS`).
  - **`motion_fade` 150ms**: an icon fading or swapping. Chrome uses 150ms (`LocationBarMediator.ICON_FADE_ANIMATION_DURATION_MS`), started after a 75ms delay.
  - **`motion_standard` 225ms**: a bar or field changing shape, e.g. search opening. Chrome uses 225ms for URL focus (`ToolbarPhone.URL_FOCUS_CHANGE_ANIMATION_DURATION_MS`) and for location-bar width changes.
  - **`motion_colour` 250ms**: colour changes, e.g. page colours in the bars. Chrome uses 250ms (`ToolbarPhone.THEME_COLOR_TRANSITION_DURATION`).
  - **`motion_emphasis` 320ms**: Search's own press spring (`Motion.kt`). Chrome has no equivalent.
- For reference, not a token: Chrome waits 30ms after a keystroke before asking for suggestions
  (`AutocompleteMediator.OMNIBOX_SUGGESTION_START_DELAY_MS`). Search instead drops stale
  answers by sequence number (`suggestSeq`), which works too.

`Motion.pressable` now reads `motion_press` and `motion_emphasis`.

Ergonomic floors taken from Chrome (Search may be bigger, never smaller):

| What | Chrome (source) | Search |
| --- | --- | --- |
| Toolbar height | 56dp (`default_action_bar_height`) | Top bar is 58dp (a 44dp box with 7dp above and below); bottom bar 56dp. Fine. |
| Address field height | 40dp pill, +8dp on focus (`modern_toolbar_background_size`, `location_bar_background_on_focus_height_increase`) | 44dp. Fine: more than the floor. |
| Address text | 16sp (`location_bar_url_text_size`) | Check: must not go below 16sp. |
| Toolbar buttons | 48dp wide (`location_bar_action_icon_width` = `min_touch_target_size`) | 48dp `touch_target`. Fine. |
| Suggestion rows | 56dp; 48dp compact (`omnibox_suggestion_content_height`) | 48dp minimum (`item_suggestion.xml`). Fine. |
| Load progress | 2dp line (`toolbar_progress_bar_height`); hides 100ms after the end | Search uses its own ring (`LoadRingView`). A different look, by design. |

## Gap analysis

Status key:
- **Have**: Search does it.
- **Partial**: Search does part of it.
- **Gap**: Chrome does it and Search can.
- **WebView limit**: Search cannot do it fully on WebView.
- **Skip**: tied to a Google account or Chrome-only service.

Batch numbers refer to the roadmap below.

### Address bar & search

| Chrome | Source | Search | Batch |
| --- | --- | --- | --- |
| Each engine's own suggestion server | prepopulated engines (`libchrome.so`) | **Gap (privacy bug)**: Yahoo, Ecosia, Brave, Startpage and Yandex sent keystrokes to Google's suggest server | 1 ✅ |
| Suggestions: search, URL, history, bookmarks | `omnibox/suggestions/basic` | Have | — |
| Switch to tab | `omnibox/suggestions/tabgroup` | Have | — |
| Clipboard link | `omnibox/suggestions/clipboard` | Have (link). Text and image: Gap | 3 |
| Refine (fill the box from a suggestion) | `BaseSuggestionView` action | Have (`sugFill`) | — |
| Delete a history suggestion (long-press) | `SuggestionHost.onDeleteMatch` | Gap | 1 ✅ |
| Inline autocomplete of sites you visit | `AutocompleteMediator` | Gap | 3 |
| Answers in the box (calculator) | `omnibox/suggestions/answer` | Gap: a local calculator answer | 1 ✅ |
| Site search shortcuts (`@bookmarks`, `@tabs`, `@history`, engine keywords) | `custom_site_search_preferences.xml` | Gap | 3 |
| Custom search engines | `custom_search_engine_preferences.xml` | Gap | 3 |
| Edit current URL row (copy, share, edit) | `omnibox/suggestions/editurl` | Partial (long-press on the address) | 3 |
| Cached zero-prefix suggestions | `CachedZeroSuggestionsManager` | Have (local recents are instant) | — |
| Ranking by visits and recency | HistoryURL provider | Partial: tiles use frecency (`SiteVisits`); suggestions don't | 3 |

### Tabs

| Chrome | Source | Search | Batch |
| --- | --- | --- | --- |
| Tab grid, groups, reorder, close all, undo close | `tab_ui` | Have | — |
| Archive tabs inactive for N days | `ChromePreferenceKeys.TAB_DECLUTTER_ARCHIVE_TIME_DELTA_HOURS` | Gap | 4 |
| Tab switcher search | `tab_ui` | Have (deck filter) | — |
| Reopen closed tab | | Have | — |

### Privacy (Chrome: Settings › Privacy and security)

| Chrome | Source | Search | Batch |
| --- | --- | --- | --- |
| Delete browsing data with a time range (15 min, 1 h, 24 h, 7 days, 4 weeks, all time) and a choice of what | `components/browsing_data/core/browsing_data_utils.h` `TimePeriod` | Partial (all, or the last 15 minutes) | 2 |
| Third-party cookies | `cookie_settings.xml` | Have | — |
| Send an opt-out request (Global Privacy Control) | `universal_opt_out_preferences.xml` | Gap. WebView limit: the header goes only on loads Search starts; `navigator.globalPrivacyControl` on every page | 1 ✅ |
| Do Not Track | `do_not_track_preferences.xml` | Folded into the opt-out above (DNT is deprecated) | 1 ✅ |
| Preload pages | `privacy_preferences.xml` | WebView limit: `androidx.webkit` prefetch is experimental | 5 |
| Lock private tabs on leaving | `IncognitoReauthSettingSwitchPreference` | Have (Night Owl lock) | — |
| Privacy Guide | `privacy_guide` | Gap: a short guided review | 4 |

### Security

| Chrome | Source | Search | Batch |
| --- | --- | --- | --- |
| Safe Browsing (standard) | `safe_browsing_preferences.xml` | Have (WebView built-in) | — |
| Enhanced Safe Browsing | `enhanced_protection_preferences.xml` | Skip (Google-account service) | — |
| Always use secure connections | `privacy_preferences.xml` | Have (HTTPS-only) | — |
| Secure DNS | `privacy_preferences.xml` | WebView limit: follows Android's Private DNS. We link to it | 4 |
| Look-alike site warning | | Have | — |
| Dangerous download warnings | | Have (risky downloads) | — |
| Safety check (one screen for every protection) | `SafetyHubFragment`, `safety_hub` | Gap | 4 |
| Remove permissions from sites unused for 60 days | `unused_site_permissions_manager.cc:74` | Gap | 2 |
| Compromised password check | | Skip (Google Password Manager) | — |

### Site settings

| Chrome | Search | Batch |
| --- | --- | --- |
| Location, camera, microphone per site | Have | — |
| JavaScript, pop-ups (global + per-site allow), images, autoplay/sound | Have, except sound | — |
| Request desktop site, per site | Gap (global only) | 4 |
| Auto-dark web content | Have (global) | — |
| Protected content (DRM) | WebView handles it in `onPermissionRequest` | — |
| Storage / data stored per site | Have | — |
| Zoom per site | Have | — |
| Notifications | WebView limit: no web notifications | — |

### Accessibility

| Chrome | Search | Batch |
| --- | --- | --- |
| Default zoom, saved zoom per site, force zoom | Have | — |
| Show zoom in the main menu | Have (Menu › Zoom) | — |
| Simplified view (offer reader mode on articles) | Partial (Reader view on request; no offer) | 4 |
| Match Android font size | Gap | 4 |

### Languages, downloads, start-up

| Chrome | Search | Batch |
| --- | --- | --- |
| Translate page | Have | — |
| Offer to translate pages in other languages | Gap | 4 |
| App language (Android 13 per-app language) | Gap: `locales_config` | 5 |
| Download location, ask where to save | Gap (SAF picker) | 5 |
| Automatically open PDFs | Partial | 5 |
| Homepage / on start-up | Partial (home after 4 h away). Custom homepage: Gap | 4 |

### Performance

| Chrome | Search | Batch |
| --- | --- | --- |
| Freeze background tabs, trim on memory pressure | Have (`onTrimMemory`, live-tab budget) | — |
| Renderer crash recovery | Have | — |
| Pre-warmed suggestion views (`PreWarmingRecycledViewPool`) | Gap: small | 3 |
| Desktop user agent | **Gap (bug)**: desktop mode claimed Chrome 120; now follows the WebView's real major version. Turning desktop off also brought back WebView's raw agent (`; wv`, `Version/4.0`), which strict sites refuse; fixed (`UserAgents`) | 1 ✅ |

## Roadmap

| Batch | Contents | Status |
| --- | --- | --- |
| 1 | Engine suggestion servers; GPC/DNT opt-out; desktop UA version; delete a history suggestion; calculator answer; motion tokens | This branch |
| 2 | Time-range data deletion with choices; auto-remove unused permissions (60 days) | Next |
| 3 | Inline autocomplete; site search shortcuts and custom engines; ranking by frecency; clipboard text; edit-URL row | |
| 4 | Safety check screen; Privacy review; per-site desktop; offer translate; offer reader view; homepage choice; Android font size; tab archive; Private DNS link | |
| 5 | Preloading (androidx.webkit), app language, download location, PDFs | |
