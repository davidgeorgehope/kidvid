#!/bin/bash
# Seed a simulator with videos and run the swipe UI test.
# Usage: ios/uitest/sim-swipe-test.sh <videos-dir> <out-dir>
set -euo pipefail
cd "$(dirname "$0")"
VIDEOS="${1:?videos dir}"; OUT="${2:-/tmp/kidvid-uitest}"
SIM_NAME="KidVid SE3"
export PATH=/opt/homebrew/bin:$PATH
UDID=$(xcrun simctl list devices | awk -v n="$SIM_NAME" -F'[()]' 'index($0,n" (") {print $2; exit}')
if [ -z "$UDID" ]; then
  UDID=$(xcrun simctl create "$SIM_NAME" com.apple.CoreSimulator.SimDeviceType.iPhone-SE-3rd-generation)
fi
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcodegen generate --spec project-uitest.yml -q
rm -rf "$OUT"; mkdir -p "$OUT"
DD="${KV_DD:-$HOME/Library/Caches/kidvid-uitest-dd}"
xcodebuild -project KidVidUITest.xcodeproj -scheme KidVidUITests -derivedDataPath "$DD" \
  -destination "id=$UDID" build-for-testing -quiet
APP="$DD/Build/Products/Debug-iphonesimulator/KidVid.app"
xcrun simctl terminate "$UDID" com.kidvid.ios 2>/dev/null || true
xcrun simctl install "$UDID" "$APP"
# Point sync at a dead host so the test library stays exactly what we seed.
xcrun simctl spawn "$UDID" defaults write com.kidvid.ios kidvid.serverURL "http://127.0.0.1:9"
DATA=$(xcrun simctl get_app_container "$UDID" com.kidvid.ios data)
rm -rf "$DATA/Documents/kidvid/videos"; mkdir -p "$DATA/Documents/kidvid/videos"
rsync -a --include='*.mp4' --include='*.jpg' --exclude='*' "$VIDEOS"/ "$DATA/Documents/kidvid/videos/"
# Keep the Mac silent: the app is built with KIDVID_UITEST (player muted), and we
# also mute host output for the run and restore it afterwards.
WAS_MUTED=$(osascript -e 'output muted of (get volume settings)' 2>/dev/null || echo true)
osascript -e 'set volume output muted true' 2>/dev/null || true
restore_mute() { if [ "$WAS_MUTED" = "false" ]; then osascript -e 'set volume output muted false' || true; fi; }
trap restore_mute EXIT
TEST_RUNNER_KV_OUT="$OUT" xcodebuild -project KidVidUITest.xcodeproj -scheme KidVidUITests \
  -derivedDataPath "$DD" -destination "id=$UDID" test-without-building \
  -resultBundlePath "$OUT/result.xcresult" 2>&1 | tail -15 || true
restore_mute
tail -3 "$OUT/summary.txt" || true
