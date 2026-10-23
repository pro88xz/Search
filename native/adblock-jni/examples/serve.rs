// Test harness only: answers the engine's decisions over HTTP on 127.0.0.1:8799.
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use adblock_jni::{cosmetic_json, hidden_css, load, should_block};

fn dec(s: &str) -> String {
    let b = s.as_bytes(); let mut out = Vec::new(); let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() + 0 && i + 2 <= b.len() - 1 {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) { out.push(v); i += 3; continue; }
        }
        out.push(if b[i] == b'+' { b' ' } else { b[i] }); i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn main() {
    let data = std::fs::read(std::env::args().nth(1).unwrap()).unwrap();
    let e = load(&data).unwrap();
    let l = TcpListener::bind("127.0.0.1:8799").unwrap();
    for s in l.incoming() {
        let mut s = match s { Ok(s) => s, Err(_) => continue };
        let mut line = String::new();
        { let mut r = BufReader::new(&s); if r.read_line(&mut line).is_err() { continue; }
          loop { let mut h = String::new(); if r.read_line(&mut h).is_err() || h == "\r\n" || h.is_empty() { break; } } }
        let path = line.split_whitespace().nth(1).unwrap_or("/").to_string();
        let (route, q) = path.split_once('?').unwrap_or((&path, ""));
        let mut p = std::collections::HashMap::new();
        for kv in q.split('&') { if let Some((k, v)) = kv.split_once('=') { p.insert(k.to_string(), dec(v)); } }
        let g = |k: &str| p.get(k).cloned().unwrap_or_default();
        let body = match route {
            "/check" => if should_block(&e, &g("url"), &g("src"), &g("types")) { "1".to_string() } else { "0".to_string() },
            "/cosmetic" => cosmetic_json(&e, &g("url")),
            "/hidden" => {
                let sp = |s: String| s.split('\n').filter(|x| !x.is_empty()).map(|x| x.to_string()).collect::<Vec<_>>();
                hidden_css(&e, &sp(g("c")), &sp(g("i")), &sp(g("e")).into_iter().collect())
            }
            _ => String::new(),
        };
        let _ = write!(s, "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body);
    }
}
