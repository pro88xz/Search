# Writes app/src/main/assets/licenses.html, Settings > Open-source licences.
#
#   python3 native/adblock-jni/licenses.py app/src/main/assets/licenses.html
#
# CRATES is what `cargo tree -e normal --target aarch64-linux-android` lists
# in native/adblock-jni, less the build-time-only macro crates; APP is the
# app's runtime libraries. Update them when either changes. The licence
# texts are read from the crates in the local Cargo registry.
import html, os, sys, glob
reg = glob.glob(os.path.expanduser('~/.cargo/registry/src/index.crates.io-*/'))[0]
def read(p): return open(p, encoding='utf-8', errors='replace').read().strip()
MPL = read(os.path.join(reg, 'adblock-0.13.3/LICENSE'))
APACHE = read(os.path.join(reg, 'regex-1.13.1/LICENSE-APACHE'))
UNICODE = read(os.path.join(reg, 'icu_collections-2.4.0/LICENSE'))
MIT = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

# (crate, version, licence used, copyright, source)
CRATES = [
 ("addr","0.15.6","MIT","Copyright (c) 2016 Rushmore Mushambi","https://github.com/addr-rs/addr"),
 ("aho-corasick","1.1.5","MIT","Copyright (c) 2015 Andrew Gallant","https://github.com/BurntSushi/aho-corasick"),
 ("arrayvec","0.7.8","MIT","Copyright (c) Ulrik Sverdrup \"bluss\" 2015-2023","https://github.com/bluss/arrayvec"),
 ("base64","0.22.1","MIT","Copyright (c) 2015 Alice Maz","https://github.com/marshallpierce/rust-base64"),
 ("bitflags","2.13.2","MIT","Copyright (c) 2014 The Rust Project Developers","https://github.com/bitflags/bitflags"),
 ("bytes","1.12.1","MIT","Copyright (c) 2018 Carl Lerche","https://github.com/tokio-rs/bytes"),
 ("cesu8","1.1.0","MIT","Copyright (c) Eric Kidd","https://github.com/emk/cesu8-rs"),
 ("cfg-if","1.0.5","MIT","Copyright (c) 2014 Alex Crichton","https://github.com/rust-lang/cfg-if"),
 ("combine","4.6.9","MIT","Copyright (c) 2015 Markus Westerlind","https://github.com/Marwes/combine"),
 ("either","1.19.0","MIT","Copyright (c) 2015 bluss","https://github.com/rayon-rs/either"),
 ("flatbuffers","25.12.19","Apache-2.0","Copyright Google Inc. and the FlatBuffers Maintainers","https://github.com/google/flatbuffers"),
 ("form_urlencoded","1.2.2","MIT","Copyright (c) 2013-2016 The rust-url developers","https://github.com/servo/rust-url"),
 ("icu_collections, icu_locale_core, icu_normalizer, icu_normalizer_data, icu_properties, icu_properties_data, icu_provider, litemap, potential_utf, tinystr, writeable, yoke, zerofrom, zerotrie, zerovec","ICU4X 2.x","Unicode-3.0","Copyright © 2020-2024 Unicode, Inc.","https://github.com/unicode-org/icu4x"),
 ("idna","1.1.0","MIT","Copyright (c) 2013-2025 The rust-url developers","https://github.com/servo/rust-url"),
 ("idna_adapter","1.2.2","MIT","Copyright (c) The rust-url developers","https://github.com/hsivonen/idna_adapter"),
 ("itertools","0.14.0","MIT","Copyright (c) 2015 bluss","https://github.com/rust-itertools/itertools"),
 ("itoa","1.0.18","MIT","Copyright (c) David Tolnay","https://github.com/dtolnay/itoa"),
 ("jni","0.21.1","MIT","Copyright (c) 2016 Prevoty, Inc. and jni-rs contributors","https://github.com/jni-rs/jni-rs"),
 ("jni-sys","0.3.1, 0.4.1","MIT","Copyright (c) 2015 The rust-jni-sys Developers","https://github.com/jni-rs/jni-sys"),
 ("log","0.4.34","MIT","Copyright (c) 2014 The Rust Project Developers","https://github.com/rust-lang/log"),
 ("memchr","2.8.3","MIT","Copyright (c) 2015 Andrew Gallant","https://github.com/BurntSushi/memchr"),
 ("percent-encoding","2.3.2","MIT","Copyright (c) 2013-2025 The rust-url developers","https://github.com/servo/rust-url"),
 ("precomputed-hash","0.1.1","MIT","Copyright (c) 2017 Emilio Cobos Álvarez","https://github.com/emilio/precomputed-hash"),
 ("psl","2.1.244","MIT","Copyright (c) 2016 Rushmore Mushambi","https://github.com/addr-rs/psl"),
 ("psl-types","2.0.11","MIT","Copyright (c) 2021 Rushmore Mushambi","https://github.com/addr-rs/psl-types"),
 ("regex, regex-automata, regex-syntax","1.13.1, 0.4.18, 0.8.11","MIT","Copyright (c) 2014 The Rust Project Developers","https://github.com/rust-lang/regex"),
 ("rustc-hash","2.1.3","MIT","Copyright (c) The Rust Project Developers","https://github.com/rust-lang/rustc-hash"),
 ("seahash","4.1.0","MIT","Copyright (c) ticki and Tom Almeida","https://gitlab.redox-os.org/redox-os/seahash"),
 ("serde, serde_core, serde_json","1.0.229, 1.0.229, 1.0.154","MIT","Copyright (c) Erick Tryzelaar and David Tolnay","https://github.com/serde-rs/serde"),
 ("smallvec","1.16.3","MIT","Copyright (c) 2018 The Servo Project Developers","https://github.com/servo/rust-smallvec"),
 ("stable_deref_trait","1.2.1","MIT","Copyright (c) 2017 Robert Grosse","https://github.com/storyyeller/stable_deref_trait"),
 ("thiserror","1.0.69","MIT","Copyright (c) David Tolnay","https://github.com/dtolnay/thiserror"),
 ("url","2.5.8","MIT","Copyright (c) 2013-2025 The rust-url developers","https://github.com/servo/rust-url"),
 ("utf8_iter","1.0.4","MIT","Copyright (c) Henri Sivonen","https://github.com/hsivonen/utf8_iter"),
 ("zmij","1.0.23","MIT","Copyright (c) David Tolnay","https://github.com/dtolnay/zmij"),
]

# (name, licence, copyright, source)
APP = [
 ("AndroidX libraries (androidx.*: Activity, AppCompat, Core, Fragment, Lifecycle, Media, RecyclerView, Room, WebKit, Work and the rest)","Apache-2.0","Copyright The Android Open Source Project","https://github.com/androidx/androidx"),
 ("Material Components for Android","Apache-2.0","Copyright Google LLC","https://github.com/material-components/material-components-android"),
 ("Kotlin standard library","Apache-2.0","Copyright JetBrains s.r.o. and Kotlin Programming Language contributors","https://github.com/JetBrains/kotlin"),
 ("kotlinx.coroutines","Apache-2.0","Copyright JetBrains s.r.o. and contributors","https://github.com/Kotlin/kotlinx.coroutines"),
 ("JetBrains Java Annotations","Apache-2.0","Copyright JetBrains s.r.o.","https://github.com/JetBrains/java-annotations"),
 ("Guava, failureaccess, listenablefuture","Apache-2.0","Copyright The Guava Authors","https://github.com/google/guava"),
 ("Error Prone annotations","Apache-2.0","Copyright The Error Prone Authors","https://github.com/google/error-prone"),
 ("J2ObjC annotations","Apache-2.0","Copyright Google Inc.","https://github.com/google/j2objc"),
 ("JSR 305 annotations","Apache-2.0","Copyright the FindBugs authors","https://github.com/findbugsproject/findbugs"),
 ("javax.inject","Apache-2.0","Copyright The JSR-330 Expert Group","https://github.com/javax-inject/javax-inject"),
 ("Checker Framework qualifiers","MIT","Copyright (c) 2004-present by the Checker Framework developers","https://github.com/typetools/checker-framework"),
 ("Firebase encoders and components, Google Data Transport","Apache-2.0","Copyright Google LLC","https://github.com/firebase/firebase-android-sdk"),
 ("Phosphor Icons (menu and Settings icons)","MIT","Copyright (c) 2023 Phosphor Icons","https://github.com/phosphor-icons/core"),
 ("Tabler Icons (bottom bar and navigation icons)","MIT","Copyright (c) 2020-2026 Paweł Kuna","https://github.com/tabler/tabler-icons"),
]

e = html.escape
def a(u, t=None): return '<a href="%s">%s</a>' % (e(u), e(t or u.replace('https://','')))
out = []
w = out.append
w('''<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Open-source licences</title>
<!-- Made by native/adblock-jni/licenses.py. Edit that, not this. -->
<style>
  :root { --bg:#FAFAFA; --fg:#141416; --muted:#6b6b70; --accent:#5E44A0; --line:rgba(20,20,22,.10); --card:#FFFFFF; }
  @media (prefers-color-scheme: dark){ :root{ --bg:#0E0E10; --fg:#EDEDED; --muted:#9a9aa0; --accent:#8B6BD8; --line:rgba(237,237,237,.10); --card:#17171A; } }
  body{ margin:0; background:var(--bg); color:var(--fg); line-height:1.6;
    font-family:-apple-system,"Roboto","Segoe UI",system-ui,sans-serif; padding:28px 22px 60px; overflow-wrap:anywhere; }
  h1{ font-size:24px; margin:0 0 4px; }
  .lead{ color:var(--muted); font-size:14px; margin:0 0 22px; }
  h2{ font-size:17px; margin:30px 0 10px; color:var(--accent); }
  p,li{ font-size:15px; }
  a{ color:var(--accent); }
  .item{ background:var(--card); border:1px solid var(--line); border-radius:14px; padding:14px 16px; margin:10px 0; }
  .item b{ font-size:15px; }
  .meta{ color:var(--muted); font-size:13px; margin-top:2px; }
  .item p{ margin:8px 0 0; font-size:14px; }
  details{ border:1px solid var(--line); border-radius:14px; margin:10px 0; background:var(--card); }
  summary{ padding:13px 16px; font-weight:600; font-size:15px; cursor:pointer; }
  pre{ margin:0; padding:0 16px 16px; white-space:pre-wrap; font:12px/1.5 ui-monospace,"Roboto Mono",monospace; color:var(--muted); }
</style></head><body>
  <h1>Open-source licences</h1>
  <p class="lead">Search is built with open-source software and open data. Thank you to everyone who makes it.</p>

  <h2>Ad and tracker blocking</h2>
  <div class="item"><b>adblock-rust</b> 0.13.3
    <div class="meta">Brave Software, Inc. &middot; Mozilla Public License 2.0</div>
    <p>The engine inside the Brave browser, and the one Search blocks ads and trackers with. Search uses it unmodified. Its source code is at '''+a("https://github.com/brave/adblock-rust")+''', and the licence is below.</p></div>
  <div class="item"><b>EasyList</b> and <b>EasyPrivacy</b>
    <div class="meta">The EasyList authors ('''+a("https://easylist.to/")+''') &middot; CC BY-SA 3.0 or GPL 3.0</div>
    <p>The ad (EasyList) and tracker (EasyPrivacy) filter lists, from '''+a("https://easylist.to/")+'''. They are dual licensed under the GNU General Public License version 3 or later and Creative Commons Attribution-ShareAlike 3.0 Unported or later; Search uses them under '''+a("https://creativecommons.org/licenses/by-sa/3.0/","CC BY-SA 3.0")+'''. Search ships them compiled into adblock-rust's binary format and otherwise unchanged, refreshes them from easylist.to while blocking is on, and shares that compiled form under the same licence. Terms: '''+a("https://easylist.to/pages/licence.html")+'''.</p></div>
  <div class="item"><b>Brave Unbreak</b>
    <div class="meta">Brave Software, Inc. &middot; Mozilla Public License 2.0</div>
    <p>Brave's fixes for sites the lists above would break, from '''+a("https://github.com/brave/adblock-lists")+'''. Search turns on the rules that let Facebook, X and LinkedIn sign-in and embedded posts through, as Brave's "Allow Facebook logins and embedded posts" setting does; the list is otherwise unchanged.</p></div>
  <div class="item"><b>Public Suffix List</b>
    <div class="meta">Mozilla Foundation and contributors &middot; Mozilla Public License 2.0</div>
    <p>How the engine tells a site from its subdomains, built into the psl library below. Source: '''+a("https://publicsuffix.org/")+'''.</p></div>

  <h2>Inside the blocking engine</h2>
  <p class="lead">Libraries compiled into adblock-rust and Search's bridge to it. Where a library offers a choice of licences, the one Search uses is named.</p>
''')
for name, ver, lic, cr, src in CRATES:
    w('  <div class="item"><b>%s</b> %s<div class="meta">%s &middot; %s</div><p>%s</p></div>\n' % (e(name), e(ver), e(cr), e(lic), a(src)))
w('''
  <h2>The app</h2>
''')
for name, lic, cr, src in APP:
    w('  <div class="item"><b>%s</b><div class="meta">%s &middot; %s</div><p>%s</p></div>\n' % (e(name), e(cr), e(lic), a(src)))
w('''
  <h2>Google services</h2>
  <p>Ads and ad consent, purchases, in-app updates and reviews, and the QR code scanner use Google Mobile Ads, the User Messaging Platform, Google Play Billing, Google Play's in-app update and review libraries, Google Play services and ML Kit. They are not open-source software, and are used under '''+a("https://developer.android.com/studio/terms","the Android Software Development Kit License")+''' and '''+a("https://developers.google.com/terms","the Google APIs Terms of Service")+'''.</p>

  <h2>Licence texts</h2>
''')
for title, text in [("MIT License", MIT), ("Apache License 2.0", APACHE), ("Mozilla Public License 2.0", MPL), ("Unicode License v3", UNICODE)]:
    w('  <details><summary>%s</summary><pre>%s</pre></details>\n' % (e(title), e(text)))
w('''  <p class="lead" style="margin-top:22px">Creative Commons Attribution-ShareAlike 3.0 Unported: '''+a("https://creativecommons.org/licenses/by-sa/3.0/legalcode")+'''. GNU General Public License 3.0: '''+a("https://www.gnu.org/licenses/gpl-3.0.html")+'''.</p>
</body></html>
''')
open(sys.argv[1], 'w', encoding='utf-8').write(''.join(out))
print(len(''.join(out)))
