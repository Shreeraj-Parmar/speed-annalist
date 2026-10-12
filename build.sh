#!/usr/bin/env bash
# Builds SpeedAnalyst.apk without Gradle or the Android SDK manager.
#
# Requirements (Ubuntu/Debian):
#   sudo apt-get install -y openjdk-17-jdk aapt apksigner dalvik-exchange zipalign
# The Android 14 (API 34) framework jar is downloaded from Maven Central (Robolectric's android-all).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
SDK_DIR="${SDK_DIR:-$ROOT/.sdk}"
ANDROID_ALL_VER="14-robolectric-10818077"
ANDROID_JAR="$SDK_DIR/android-all-$ANDROID_ALL_VER.jar"
KEYSTORE="$ROOT/signing/speedanalyst.keystore"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
OUT="$ROOT/SpeedAnalyst.apk"

mkdir -p "$SDK_DIR"
if [ ! -f "$ANDROID_JAR" ]; then
  echo "==> Downloading Android framework jar ($ANDROID_ALL_VER)"
  curl -fSL -o "$ANDROID_JAR.tmp" \
    "https://repo1.maven.org/maven2/org/robolectric/android-all/$ANDROID_ALL_VER/android-all-$ANDROID_ALL_VER.jar"
  mv "$ANDROID_JAR.tmp" "$ANDROID_JAR"
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

if [ ! -f "$APP/res/mipmap-mdpi/ic_launcher.png" ]; then
  echo "==> Drawing launcher icon"
  python3 "$ROOT/tools/make_icon.py" "$APP/res"
fi

echo "==> Compiling resources"
aapt2 compile --dir "$APP/res" -o "$BUILD/res.zip"
aapt2 link -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$BUILD/app.unaligned.apk" "$BUILD/res.zip"

echo "==> Compiling Java"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 \
  -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> Converting to DEX"
dalvik-exchange --dex --min-sdk-version=24 --output="$BUILD/dex/classes.dex" "$BUILD/classes"

echo "==> Packaging"
cp "$BUILD/app.unaligned.apk" "$BUILD/app.withdex.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/app.withdex.apk" classes.dex)
zipalign -f -p 4 "$BUILD/app.withdex.apk" "$BUILD/app.aligned.apk"

echo "==> Signing"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass speedanalyst -keypass speedanalyst \
    -alias speedanalyst -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Speed Analyst, O=Speed Analyst, C=IN"
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:speedanalyst --key-pass pass:speedanalyst \
  --ks-key-alias speedanalyst --out "$OUT" "$BUILD/app.aligned.apk"
apksigner verify "$OUT"
rm -f "$OUT.idsig"
echo "==> Built $OUT ($(du -h "$OUT" | cut -f1))"
