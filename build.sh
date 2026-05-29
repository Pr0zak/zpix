#!/bin/bash
set -euo pipefail

SDK=/home/spider/tools/android-sdk
BT=$SDK/build-tools/35.0.0
AJAR=$SDK/platforms/android-35/android.jar
PROJ=/home/spider/zandframe
OUT=$PROJ/build
KS=$PROJ/debug.keystore

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res-compiled.zip"

echo "[2/6] aapt2 link (manifest + assets -> base apk, generate R.java)"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  --min-sdk-version 22 \
  --target-sdk-version 22 \
  --java "$OUT/gen" \
  "$OUT/res-compiled.zip"

echo "[3/6] javac"
find "$PROJ/src" "$OUT/gen" -name '*.java' > "$OUT/srcs.txt"
javac -source 8 -target 8 -nowarn -Xlint:none \
  -classpath "$AJAR" \
  -d "$OUT/classes" \
  @"$OUT/srcs.txt"

echo "[4/6] d8 -> classes.dex"
CLASSES=$(find "$OUT/classes" -name '*.class')
"$BT/d8" --min-api 22 --lib "$AJAR" --output "$OUT/dex" $CLASSES

echo "[5/6] package dex into apk + zipalign"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT/dex" && "$BT/aapt" add "$OUT/unaligned.apk" classes.dex >/dev/null )
"$BT/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/zpix-aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  echo "  creating debug keystore"
  keytool -genkeypair -keystore "$KS" -alias zpix -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=zpix" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias zpix \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/zpix.apk" "$OUT/zpix-aligned.apk"

echo
echo "BUILT: $OUT/zpix.apk"
ls -la "$OUT/zpix.apk"
