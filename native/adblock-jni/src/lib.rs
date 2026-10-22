//! The bridge from the Search browser (com.search.browser.AdblockEngine) to
//! Brave's adblock-rust engine.
//!
//! One engine is live at a time, behind a lock that is only ever held long
//! enough to clone or swap an `Arc` - WebView asks about requests from
//! several threads at once, and a list update replaces the engine while
//! pages are loading. A check that started on the old engine finishes on it;
//! the old engine is freed when the last such check lets go of it.
//!
//! Nothing here may unwind into the JVM: every entry point catches a panic
//! and answers as if there were no engine, which the app treats as "use the
//! built-in host list".

use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, RwLock};

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

static ENGINE: RwLock<Option<Arc<Engine>>> = RwLock::new(None);

/// No engine is loaded - the caller falls back to its own list.
const NO_ENGINE: jint = -1;
const ALLOW: jint = 0;
const BLOCK: jint = 1;

/// Selectors pulled from a page and sent back to it are capped: a page is
/// free to call in with as many names as it likes, and none of them should
/// cost more than a glance.
const MAX_NAMES: usize = 2000;
const MAX_NAME_LEN: usize = 256;

fn current() -> Option<Arc<Engine>> {
    match ENGINE.read() {
        Ok(guard) => guard.clone(),
        Err(poisoned) => poisoned.into_inner().clone(),
    }
}

fn install(engine: Engine) {
    let next = Some(Arc::new(engine));
    match ENGINE.write() {
        Ok(mut guard) => *guard = next,
        Err(poisoned) => *poisoned.into_inner() = next,
    }
}

/// Brave's list blocks the Facebook, X and LinkedIn sign-in and embed
/// scripts on other sites, and lets them through again under these tags -
/// Brave's "Allow Facebook logins and embedded posts" and its neighbours.
/// They are on here, as they were with the app's own list: "Continue with
/// Facebook" and an embedded post are things the user asked for. The pixels
/// and beacons stay blocked.
///
/// Tags are not part of a serialized engine, so rather than turn them on
/// after every load, [build] drops the tag from the rules that carry one -
/// a tagged rule with its tag on is the same rule untagged.
const EMBED_TAGS: &[&str] = &["fb-embeds", "twitter-embeds", "linked-in-embeds"];

fn untag(line: &str) -> Option<String> {
    if !line.contains("tag=") || line.contains('#') {
        return None;
    }
    let dollar = line.rfind('$')?;
    let options: Vec<&str> = line[dollar + 1..].split(',').collect();
    let kept: Vec<&str> = options
        .iter()
        .copied()
        .filter(|o| !o.strip_prefix("tag=").is_some_and(|t| EMBED_TAGS.contains(&t)))
        .collect();
    if kept.len() == options.len() {
        return None;
    }
    Some(if kept.is_empty() {
        line[..dollar].to_string()
    } else {
        format!("{}${}", &line[..dollar], kept.join(","))
    })
}

fn with_embed_tags_on(list: String) -> String {
    if !list.contains("tag=") {
        return list;
    }
    let mut out = String::with_capacity(list.len());
    for line in list.lines() {
        match untag(line) {
            Some(untagged) => out.push_str(&untagged),
            None => out.push_str(line),
        }
        out.push('\n');
    }
    out
}

/// Builds an engine from filter list texts (EasyList syntax).
pub fn build(lists: Vec<String>) -> Engine {
    let mut set = FilterSet::new(false);
    for list in lists {
        set.add_filter_list(with_embed_tags_on(list), ParseOptions::default());
    }
    Engine::new_with_filter_set(set)
}

/// An engine from `Engine::serialize` output, or None if it will not load.
pub fn load(data: &[u8]) -> Option<Engine> {
    let mut engine = Engine::default();
    engine.deserialize(data).ok().map(|_| engine)
}

/// Whether `url`, asked for by a page at `source_url`, should be blocked.
///
/// `request_types` is the kind of request - "script", "image" - or, when
/// the app cannot tell, the kinds it could be, comma separated. Android does
/// not say what a request is for, and a script with no ".js" on its name
/// looks the same as a fetch. A request that could be several things is
/// blocked when a rule for any of them blocks it and no rule for any of them
/// excepts it: an exception written for the fetch it really is still holds
/// when the app had to guess. An "important" rule wins over exceptions, as
/// it does everywhere.
pub fn should_block(engine: &Engine, url: &str, source_url: &str, request_types: &str) -> bool {
    let mut blocked = false;
    for kind in request_types.split(',') {
        let Ok(request) = Request::new(url, source_url, kind.trim(), "get") else { return false };
        let r = engine.check_network_request(&request);
        if r.important {
            return true;
        }
        if r.exception.is_some() {
            return false;
        }
        blocked |= r.filter.is_some();
    }
    blocked
}

/// What a freshly built engine must do before it replaces a working one: a
/// list that came down truncated or as an error page builds without
/// complaint, and blocks nothing.
fn works(engine: &Engine) -> bool {
    should_block(engine, "https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://news.example/", "script")
        && should_block(engine, "https://www.google-analytics.com/g/collect?v=2", "https://news.example/", "ping")
        && !should_block(engine, "https://news.example/static/app.js", "https://news.example/", "script")
}

/// What hides an element. One rule per selector, so a selector this
/// WebView does not understand drops only its own rule.
const HIDE: &str = "{display:none!important}\n";

/// The page's element hiding as JSON: {"css": the page's own hide rules and
/// the generic ones that are not about a class or id, "exceptions": for
/// [hidden_css], "generichide": the page asks for no generic rules}.
/// Scriptlets and procedural filters are left out - the app runs no
/// list-supplied script on a page.
pub fn cosmetic_json(engine: &Engine, url: &str) -> String {
    let r = engine.url_cosmetic_resources(url);
    let mut hide: Vec<&String> = r.hide_selectors.iter().collect();
    hide.sort();
    let mut css = String::new();
    for s in hide {
        css.push_str(s);
        css.push_str(HIDE);
    }
    let mut exceptions: Vec<&String> = r.exceptions.iter().collect();
    exceptions.sort();
    serde_json::json!({
        "css": css,
        "exceptions": exceptions,
        "generichide": r.generichide,
    })
    .to_string()
}

/// The generic rules for these classes and ids, as CSS.
pub fn hidden_css(engine: &Engine, classes: &[String], ids: &[String], exceptions: &HashSet<String>) -> String {
    let mut css = String::new();
    for s in engine.hidden_class_id_selectors(classes, ids, exceptions) {
        css.push_str(&s);
        css.push_str(HIDE);
    }
    css
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|s| s.into())
}

fn get_strings(env: &mut JNIEnv, array: &JObjectArray, limit: usize, max_len: usize) -> Vec<String> {
    let mut out = Vec::new();
    if array.is_null() {
        return out;
    }
    let len = env.get_array_length(array).unwrap_or(0).max(0) as usize;
    for i in 0..len.min(limit) {
        let Ok(item) = env.get_object_array_element(array, i as i32) else { break };
        let item = JString::from(item);
        if let Some(s) = get_string(env, &item) {
            if s.len() <= max_len {
                out.push(s);
            }
        }
        let _ = env.delete_local_ref(item);
    }
    out
}

fn new_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Loads a serialized engine and makes it the live one. False leaves the
/// live engine as it was.
#[no_mangle]
pub extern "system" fn Java_com_search_browser_AdblockEngine_nativeLoad(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jboolean {
    let r = catch_unwind(AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(&data).ok()?;
        let engine = load(&bytes)?;
        install(engine);
        Some(())
    }));
    match r {
        Ok(Some(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Builds an engine from filter list texts and returns it serialized, for
/// [nativeLoad] and the cache on disk, or null if it does not block what
/// any working engine blocks. The live engine is not touched.
#[no_mangle]
pub extern "system" fn Java_com_search_browser_AdblockEngine_nativeBuild(
    mut env: JNIEnv,
    _class: JClass,
    lists: JObjectArray,
) -> jbyteArray {
    let r = catch_unwind(AssertUnwindSafe(|| {
        let lists = get_strings(&mut env, &lists, 16, usize::MAX);
        if lists.is_empty() {
            return None;
        }
        let engine = build(lists);
        if !works(&engine) {
            return None;
        }
        let data = engine.serialize();
        drop(engine);
        env.byte_array_from_slice(&data).ok().map(|a| a.into_raw())
    }));
    r.ok().flatten().unwrap_or(std::ptr::null_mut())
}

/// BLOCK, ALLOW, or NO_ENGINE when none is loaded.
#[no_mangle]
pub extern "system" fn Java_com_search_browser_AdblockEngine_nativeCheck(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jint {
    let r = catch_unwind(AssertUnwindSafe(|| {
        let engine = current()?;
        let url = get_string(&mut env, &url)?;
        let source = get_string(&mut env, &source_url).unwrap_or_default();
        let kind = get_string(&mut env, &request_type).unwrap_or_else(|| "other".into());
        Some(if should_block(&engine, &url, &source, &kind) { BLOCK } else { ALLOW })
    }));
    r.ok().flatten().unwrap_or(NO_ENGINE)
}

/// See [cosmetic_json]; null when no engine is loaded.
#[no_mangle]
pub extern "system" fn Java_com_search_browser_AdblockEngine_nativeCosmetic(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        let engine = current()?;
        let url = get_string(&mut env, &url)?;
        Some(new_jstring(&mut env, &cosmetic_json(&engine, &url)))
    }));
    r.ok().flatten().unwrap_or(std::ptr::null_mut())
}

/// See [hidden_css]; empty when no engine is loaded.
#[no_mangle]
pub extern "system" fn Java_com_search_browser_AdblockEngine_nativeHidden(
    mut env: JNIEnv,
    _class: JClass,
    classes: JObjectArray,
    ids: JObjectArray,
    exceptions: JObjectArray,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        let engine = current()?;
        let classes = get_strings(&mut env, &classes, MAX_NAMES, MAX_NAME_LEN);
        let ids = get_strings(&mut env, &ids, MAX_NAMES, MAX_NAME_LEN);
        let exceptions: HashSet<String> = get_strings(&mut env, &exceptions, MAX_NAMES, MAX_NAME_LEN).into_iter().collect();
        Some(new_jstring(&mut env, &hidden_css(&engine, &classes, &ids, &exceptions)))
    }));
    r.ok().flatten().unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::untag;

    #[test]
    fn untags_only_the_embed_tags() {
        assert_eq!(untag("@@||connect.facebook.net^*/sdk.js$tag=fb-embeds").as_deref(), Some("@@||connect.facebook.net^*/sdk.js"));
        assert_eq!(untag("@@||x.com^$script,tag=twitter-embeds").as_deref(), Some("@@||x.com^$script"));
        assert_eq!(untag("@@||a.com^$tag=other"), None);
        assert_eq!(untag("||a.com^$third-party"), None);
        assert_eq!(untag("example.com##.tag=x"), None);
    }
}
