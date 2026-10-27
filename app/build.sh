#!/bin/bash
set -euo pipefail

# Project root = directory containing this script (works from /workspace/app or a copy).
PROJECT="$(cd "$(dirname "$0")" && pwd)"

# Resolve Android SDK / build-tools without requiring a macOS Homebrew layout.
resolve_sdk() {
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/platforms" ]]; then
        echo "$ANDROID_HOME"
        return
    fi
    if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/platforms" ]]; then
        echo "$ANDROID_SDK_ROOT"
        return
    fi
    for candidate in \
        "$HOME/android-sdk" \
        "$HOME/Android/Sdk" \
        "/opt/android-sdk" \
        "/opt/homebrew/share/android-commandlinetools" \
        "/usr/lib/android-sdk"
    do
        if [[ -d "$candidate/platforms" && -d "$candidate/build-tools" ]]; then
            echo "$candidate"
            return
        fi
    done
    echo "ERROR: Android SDK not found. Set ANDROID_HOME or install platforms;android-30 + build-tools." >&2
    exit 1
}

SDK="$(resolve_sdk)"

# Prefer build-tools 35.0.0, else newest available.
if [[ -d "$SDK/build-tools/35.0.0" ]]; then
    BT="$SDK/build-tools/35.0.0"
else
    BT="$(ls -1d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -z "${BT:-}" || ! -x "$BT/aapt2" ]]; then
    echo "ERROR: build-tools with aapt2 not found under $SDK/build-tools" >&2
    exit 1
fi

PLATFORM="$SDK/platforms/android-30/android.jar"
if [[ ! -f "$PLATFORM" ]]; then
    echo "ERROR: missing $PLATFORM (sdkmanager \"platforms;android-30\")" >&2
    exit 1
fi

JAVAC="${JAVAC:-$(command -v javac)}"
if [[ -z "$JAVAC" ]]; then
    echo "ERROR: javac not found" >&2
    exit 1
fi

SRC="$PROJECT/src"
RES="$PROJECT/res"
GEN="$PROJECT/gen"
OBJ="$PROJECT/obj"
BIN="$PROJECT/bin"

# Clean
rm -rf "$GEN"/* "$OBJ"/* "$BIN"/* 2>/dev/null || true
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
    --min-api 21 \
    --output "$BIN" \
    $(find "$OBJ" -name "*.class")

echo "=== Step 5: Add DEX to APK ==="
cp "$BIN/kidvid.unaligned.apk" "$BIN/kidvid.unsigned.apk"
(
    cd "$BIN"
    zip -j kidvid.unsigned.apk classes.dex
)

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

echo "=== Step 7: Zipalign ==="
"$BT/zipalign" -f 4 "$BIN/kidvid.unsigned.apk" "$BIN/kidvid.aligned.apk"

echo "=== Step 8: Sign APK ==="
"$BT/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --out "$PROJECT/kidvid.apk" \
    "$BIN/kidvid.aligned.apk"

echo ""
echo "=== BUILD SUCCESS ==="
ls -lh "$PROJECT/kidvid.apk"
