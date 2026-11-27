#!/usr/bin/env bash
set -euo pipefail

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

PROJ="/home/user/double_joy_android_apk"
SDK="/tmp/android_sdk"
AAPT2="$SDK/android-14/aapt2"
D8="$SDK/android-14/d8"
ZIPALIGN="$SDK/android-14/zipalign"
APKSIGNER="$SDK/android-14/apksigner"
ANDROID_JAR="$SDK/android-34/android.jar"

WORK="/tmp/apk_build_work"
OUT_APK="/home/user/Двойная_радость_Автозахват.apk"

rm -rf "$WORK"
mkdir -p "$WORK/compiled_res" "$WORK/gen" "$WORK/classes" "$WORK/dex"

echo "[1/6] Building APK-exclusive solver.html and syncing stages_config.json into assets..."
python3 "$PROJ/make_apk_html.py"

echo "[2/6] Compiling resources with aapt2..."
"$AAPT2" compile --dir "$PROJ/res" -o "$WORK/compiled_res/res.zip"

echo "[3/6] Linking resources & generating R.java..."
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  -o "$WORK/unsigned_unaligned.apk" \
  --java "$WORK/gen" \
  "$WORK/compiled_res/res.zip"

echo "[4/6] Compiling Java sources with javac (Java 11 -> bytecode 8)..."
find "$PROJ/src" "$WORK/gen" -name "*.java" > "$WORK/sources.txt"
javac -encoding UTF-8 -source 1.8 -target 1.8 \
  -classpath "$ANDROID_JAR" \
  -d "$WORK/classes" \
  @"$WORK/sources.txt"

echo "[5/6] Converting .class files to classes.dex with d8..."
find "$WORK/classes" -name "*.class" > "$WORK/classes.txt"
"$D8" --lib "$ANDROID_JAR" --min-api 24 --output "$WORK/dex" @"$WORK/classes.txt"

(cd "$WORK/dex" && zip -q "$WORK/unsigned_unaligned.apk" classes.dex)

echo "[6/6] Zipaligning and signing APK (v1 + v2 + v3 schemes)..."
"$ZIPALIGN" -f -p 4 "$WORK/unsigned_unaligned.apk" "$WORK/unsigned_aligned.apk"

KEYSTORE="$PROJ/release-key.jks"
KEYTOOL="/usr/lib/jvm/jdk-11/bin/keytool"
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" \
    -alias doublejoy \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass doublejoy2026 -keypass doublejoy2026 \
    -dname "CN=DoubleJoy Solver, OU=Tools, O=DoubleJoy, L=Barnaul, ST=Altai, C=RU"
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias doublejoy \
  --ks-pass pass:doublejoy2026 \
  --key-pass pass:doublejoy2026 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$WORK/signed.apk" \
  "$WORK/unsigned_aligned.apk"

"$APKSIGNER" verify --verbose "$WORK/signed.apk"
cp "$WORK/signed.apk" "$OUT_APK"
ls -lh "$OUT_APK"
