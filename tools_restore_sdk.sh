#!/usr/bin/env bash
# Восстановление Android SDK в /tmp (не занимает бюджет рабочего пространства).
set -e
SDK=/tmp/android_sdk
mkdir -p "$SDK/staging" && cd "$SDK/staging"
[ -f .bt_done ] || { curl -fsSL --retry 3 -o bt.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip && unzip -q bt.zip -d bt && rm -f bt.zip && touch .bt_done; }
[ -f .pf_done ] || { curl -fsSL --retry 3 -o pf.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip || curl -fsSL --retry 3 -o pf.zip https://dl.google.com/android/repository/platform-34_r01.zip; unzip -q pf.zip -d pf && rm -f pf.zip && touch .pf_done; }
AAPT2=$(find bt -name aapt2 -type f | head -1); JAR=$(find pf -name android.jar | head -1)
test -n "$AAPT2" && test -n "$JAR"
mkdir -p "$SDK/android-34"
ln -sfn "$(cd "$(dirname "$AAPT2")" && pwd)" "$SDK/android-14"
ln -sfn "$PWD/$(dirname "$JAR")/android.jar" "$SDK/android-34/android.jar"
rm -rf pf/android-34/data pf/android-34/skins pf/android-34/templates
"$SDK/android-14/aapt2" version && echo "SDK OK → $SDK"
