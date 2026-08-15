#!/usr/bin/env bash
set -e

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_VER="11076708"  # Google command-line tools build (latest stable line)
echo ">>> Installing Android SDK to $ANDROID_HOME"

mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VER}_latest.zip" -o cmdline-tools.zip
rm -rf cmdline-tools-tmp
unzip -q cmdline-tools.zip -d cmdline-tools-tmp
# The tools expect to live under cmdline-tools/latest/
rm -rf "$ANDROID_HOME/cmdline-tools/latest"
mv cmdline-tools-tmp/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
rm -rf cmdline-tools.zip cmdline-tools-tmp

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# Accept licenses, then install the packages this project builds against.
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;35.0.0"

# Point the project at this SDK.
echo "sdk.dir=$ANDROID_HOME" > /workspaces/Search/local.properties
echo ">>> Android SDK ready at $ANDROID_HOME"
