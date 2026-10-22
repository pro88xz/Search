#!/usr/bin/env bash
# Rebuilds Brave's ad-blocking engine for the app:
#
#   app/src/main/jniLibs/<abi>/libadblock_jni.so   the engine, for each ABI
#   app/src/main/assets/adblock/engine.dat         EasyList + EasyPrivacy +
#                                                  Brave Unbreak, compiled
#
# Run it before a release to ship current lists (the app also refreshes them
# itself every few days), and after any change to this crate or its adblock
# version - engine.dat is only readable by the adblock version that wrote it,
# and the app falls back to its small built-in list if it cannot read it.
#
# A change to Cargo.lock also changes what Settings > Open-source licences
# must list: update licenses.py and run it.
#
# Needs: rustup targets aarch64-linux-android armv7-linux-androideabi
# x86_64-linux-android, cargo-ndk, and ANDROID_NDK_HOME (NDK r28 or later,
# for 16 KB pages).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/../../app/src/main"
lists="$(mktemp -d)"
trap 'rm -rf "$lists"' EXIT

cd "$here"
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
  cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 24 -o "$app/jniLibs" build --release

curl -fsSL --retry 3 -o "$lists/easylist.txt" https://easylist.to/easylist/easylist.txt
curl -fsSL --retry 3 -o "$lists/easyprivacy.txt" https://easylist.to/easylist/easyprivacy.txt
curl -fsSL --retry 3 -o "$lists/brave-unbreak.txt" https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt
mkdir -p "$app/assets/adblock"
cargo run --release --example build_engine -- "$app/assets/adblock/engine.dat" \
  "$lists/easylist.txt" "$lists/easyprivacy.txt" "$lists/brave-unbreak.txt"
