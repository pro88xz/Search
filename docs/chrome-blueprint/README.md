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
| Clipboard link and text | `omnibox/suggestions/clipboard` | Have: link, and text to search for ("Text you copied"). Image: Gap | 3 ✅ |
| Refine (fill the box from a suggestion) | `BaseSuggestionView` action | Have (`sugFill`) | — |
| Delete a history suggestion (long-press) | `SuggestionHost.onDeleteMatch` | Gap | 1 ✅ |
| Inline autocomplete of sites you visit | `AutocompleteMediator` | Gap. Deferred: rewriting the field while the keyboard is composing a word is where IME bugs live (Chrome has a dedicated model for it, `SpannableAutocompleteEditTextModel`); needs testing on a phone with Gboard and Samsung Keyboard | 3 |
| Answers in the box (calculator) | `omnibox/suggestions/answer` | Gap: a local calculator answer | 1 ✅ |
| Site search shortcuts (`@bookmarks`, `@tabs`, `@history`, engine keywords) | `custom_site_search_preferences.xml` | Have: `@bookmarks`, `@history`, `@tabs` (`SearchScopes`), searched on the phone, Enter opens the best match. Engine keywords: Gap | 3 ✅ |
| Custom search engines | `custom_search_engine_preferences.xml` | Have: one engine of the user's own (name + address with `%s`), added, changed or removed under Settings › Search engine. It gets no suggestions rather than Google's | 3 ✅ |
| Edit current URL row (copy, share, edit) | `omnibox/suggestions/editurl` | Have: the page's row first when search opens on it (tap copies, hold shares, arrow edits). **Privacy bug fixed with it**: the prefilled address was treated as typing and sent to the suggest server on every tap of the bar | 3 ✅ |
| Cached zero-prefix suggestions | `CachedZeroSuggestionsManager` | Have (local recents are instant) | — |
| Ranking by visits and recency | HistoryURL provider | Have: history matches ranked by the same frecency as home's tiles (`SiteVisits.scores`), bookmarks boosted | 3 ✅ |

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
| Delete browsing data with a time range (15 min, 1 h, 24 h, 7 days, 4 weeks, all time) and a choice of what | `components/browsing_data/core/browsing_data_utils.h` `TimePeriod` | History and shortcuts by any of Chrome's ranges; each kind of data cleared on its own. WebView limit: cookies, cache and site storage clear only whole | 2 ✅ |
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
| Safety check (one screen for every protection) | `SafetyHubFragment`, `safety_hub` | Have: top of Search Security; each protection that is off, one tap to turn on; WebView version with a Play link | 4 ✅ |
| Remove permissions from sites unused for 60 days | `unused_site_permissions_manager.cc:74` | Have: camera/mic, location, pop-ups; daily check, on by default | 2 ✅ |
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
| Match Android font size | Gap. Deferred: text zoom is worked out in four places (new tabs, per-site zoom, the zoom panel, the text-size presets), which need one shared rule first | 4 |

### Languages, downloads, start-up

| Chrome | Search | Batch |
| --- | --- | --- |
| Translate page | Have | — |
| Offer to translate pages in other languages | Have: from the page's `lang`, once per site per session, never in Night Owl (Settings › Accessibility › Languages) | 4 ✅ |
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
| 2 | Time-range data deletion with choices; auto-remove unused permissions (60 days) | This branch |
| 3 | Inline autocomplete; site search shortcuts and custom engines; `@` shortcuts ✅; ranking by frecency ✅; clipboard text ✅; edit-URL row ✅; custom engine ✅ | Partly on this branch |
| 4 | Safety check ✅; Privacy review; per-site desktop; offer translate ✅; offer reader view; homepage choice; Android font size; tab archive; Private DNS link | Partly on this branch |
| 5 | Preloading (androidx.webkit), app language, download location, PDFs | |

### Deferred, and why

These need a phone to test on, or a decision from the owner, before they are worth doing:

- **Inline autocomplete.** Rewriting the field while the keyboard is composing a word is where
  IME bugs (doubled letters, lost input) come from; test on Gboard and Samsung Keyboard first.
- **Archiving inactive tabs.** Chrome moves tabs unopened for weeks into an "Inactive tabs"
  section of the tab switcher. That needs a last-opened time saved with each tab
  (`SessionStore`) and a new section in the deck: a visible UI change to try on a device.
- **Desktop site per site.** WebView sets the user agent per view, not per navigation, so
  switching it as a link crosses into a remembered site means reloading inside a navigation
  callback, which the HANDOFF warns can crash the app. Needs a careful design and device testing.
- **Match Android font size.** Text zoom is worked out in four places; they need one shared rule.
- **Homepage choice.** Home is `home.html` with the feed and its ad; letting another page be
  home changes what the ad and feed code can assume. An owner decision.
- **App language.** The app's text is written in the code in English only, so a per-app
  language setting would have nothing to switch to until strings are translated.
- **Preloading pages.** The androidx.webkit version in use (1.12.1) has no prefetch API; newer
  ones have it as experimental.
