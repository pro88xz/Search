//! Builds the engine the app ships (app/src/main/assets/adblock/engine.dat)
//! from filter lists, then loads it back and checks it blocks what it should
//! and leaves alone what it should.
//!
//!   cargo run --release --example build_engine -- OUT.dat LIST.txt [LIST.txt...]

use std::time::Instant;

use adblock_jni::{build, cosmetic_json, load, should_block};

/// (url, page, type, blocked?)
const CASES: &[(&str, &str, &str, bool)] = &[
    ("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://news.example/", "script", true),
    ("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://news.example/", "script", true),
    ("https://www.google-analytics.com/analytics.js", "https://news.example/", "script", true),
    ("https://www.googletagmanager.com/gtm.js?id=GTM-1", "https://news.example/", "script", true),
    ("https://connect.facebook.net/en_US/fbevents.js", "https://news.example/", "script", true),
    ("https://static.hotjar.com/c/hotjar-1.js", "https://news.example/", "script", true),
    ("https://cdn.taboola.com/libtrc/site/loader.js", "https://news.example/", "script", true),
    ("https://ib.adnxs.com/ut/v3/prebid", "https://news.example/", "xmlhttprequest", true),
    ("https://sb.scorecardresearch.com/beacon.js", "https://news.example/", "script", true),
    ("https://news.example/static/app.js", "https://news.example/", "script", false),
    ("https://news.example/images/photo.jpg", "https://news.example/", "image", false),
    ("https://cdn.jsdelivr.net/npm/react@18.3.1/umd/react.production.min.js", "https://news.example/", "script", false),
    ("https://fonts.gstatic.com/s/inter/v13/a.woff2", "https://news.example/", "font", false),
    ("https://www.youtube.com/embed/abc", "https://news.example/", "sub_frame", false),
    ("https://connect.facebook.net/en_US/sdk.js", "https://news.example/", "script", false),
    ("https://www.google.com/recaptcha/api.js", "https://news.example/", "script", false),
    ("https://js.stripe.com/v3/", "https://shop.example/", "script", false),
    // A script without ".js", and a fetch, as the app sees them: it cannot
    // tell, so it asks about both.
    ("https://www.googletagmanager.com/gtag/js?id=G-1", "https://news.example/", "script,xmlhttprequest", true),
    ("https://news.example/api/articles?page=2", "https://news.example/", "script,xmlhttprequest", false),
    ("https://cdn.example-cdn.com/o?x=1&fqdn=www.wsj.com", "https://www.wsj.com/", "script,xmlhttprequest", false),
];

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("usage: build_engine OUT.dat LIST.txt [LIST.txt...]");
        std::process::exit(2);
    }
    let lists: Vec<String> = args[1..].iter().map(|p| std::fs::read_to_string(p).expect(p)).collect();

    let t = Instant::now();
    let data = build(lists).serialize();
    println!("built in {:?}: {} bytes", t.elapsed(), data.len());

    let t = Instant::now();
    let engine = load(&data).expect("the engine just built does not load");
    println!("loaded in {:?}", t.elapsed());

    let mut failed = 0;
    for (url, page, kind, want) in CASES {
        let t = Instant::now();
        let got = should_block(&engine, url, page, kind);
        let ok = got == *want;
        if !ok {
            failed += 1;
        }
        println!("{} {:5} {:?} {} ({})", if ok { "ok  " } else { "FAIL" }, got, t.elapsed(), url, kind);
    }
    let css = cosmetic_json(&engine, "https://www.example.com/");
    println!("cosmetic for a plain site: {} bytes", css.len());
    if failed > 0 {
        eprintln!("{} checks failed", failed);
        std::process::exit(1);
    }
    std::fs::write(&args[0], &data).expect("write");
    println!("wrote {}", args[0]);
}
