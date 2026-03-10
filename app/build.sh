#!/bin/bash
set -e

SDK="/opt/homebrew/share/android-commandlinetools"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-30/android.jar"
JAVAC="/usr/bin/javac"

PROJECT="/tmp/kidvid"
SRC="$PROJECT/src"
RES="$PROJECT/res"
GEN="$PROJECT/gen"
OBJ="$PROJECT/obj"
BIN="$PROJECT/bin"

# Clean
rm -rf "$GEN"/* "$OBJ"/* "$BIN"/*
mkdir -p "$GEN" "$OBJ" "$BIN"

echo "=== Step 1: Compile resources with aapt2 ==="
"$BT/aapt2" compile --dir "$RES" -o "$BIN/resources.zip"

echo "=== Step 2: Link resources ==="
"$BT/aapt2" link \
    -o "$BIN/kidvid.unaligned.apk" \
    -I "$PLATFORM" \
    --manifest "$PROJECT/AndroidManifest.xml" \
    --java "$GEN" \
    --auto-add-overlay \
    "$BIN/resources.zip"

echo "=== Step 3: Compile Java ==="
find "$GEN" "$SRC" -name "*.java" > "$BIN/sources.txt"
"$JAVAC" \
    -source 8 -target 8 \
    -bootclasspath "$PLATFORM" \
    -classpath "$PLATFORM" \
    -d "$OBJ" \
    @"$BIN/sources.txt"

echo "=== Step 4: Convert to DEX ==="
"$BT/d8" \
    --lib "$PLATFORM" \
    --min-api 30 \
    --output "$BIN" \
    $(find "$OBJ" -name "*.class")

echo "=== Step 5: Add DEX to APK ==="
cp "$BIN/kidvid.unaligned.apk" "$BIN/kidvid.unsigned.apk"
cd "$BIN"
zip -j kidvid.unsigned.apk classes.dex
cd "$PROJECT"

echo "=== Step 6: Create debug keystore ==="
KEYSTORE="$PROJECT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android \
        -keypass android \
        -alias androiddebugkey \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Debug,O=Android,C=US"
fi

echo "=== Step 7: Sign APK ==="
"$BT/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$BIN/kidvid.signed.apk" \
    "$BIN/kidvid.unsigned.apk"

echo "=== Step 8: Zipalign ==="
"$BT/zipalign" -f 4 "$BIN/kidvid.signed.apk" "$PROJECT/kidvid.apk"

echo ""
echo "=== BUILD SUCCESS ==="
ls -lh "$PROJECT/kidvid.apk"
