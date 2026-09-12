#!/usr/bin/env bash
# clone-from-android.sh — INTENT / STUB for a follow-up PR
#
# Goal: copy the Android KidVid library onto an iPhone's KidVid Documents tree:
#   Documents/kidvid/videos/*.mp4
#   Documents/kidvid/pins.json      (optional)
#   Documents/kidvid/manifest.json  (optional)
#
# Prerequisites (not automated here):
#   - adb connected to the Android KidVid phone
#   - iPhone with KidVid installed at least once (creates the container)
#   - One of: Finder file sharing, libimobiledevice + ifuse, or Apple Configurator

set -euo pipefail

OUT_DIR="${1:-./android-kidvid-export}"
ANDROID_VIDEOS_CANDIDATES=(
  "/sdcard/kidvid/videos"
  "/storage/emulated/0/kidvid/videos"
)

echo "==> Exporting Android library to ${OUT_DIR}"
mkdir -p "${OUT_DIR}/videos"

pulled=0
for remote in "${ANDROID_VIDEOS_CANDIDATES[@]}"; do
  if adb shell "test -d '${remote}'" 2>/dev/null; then
    echo "Pulling ${remote} ..."
    adb pull "${remote}/." "${OUT_DIR}/videos/"
    pulled=1
    break
  fi
done

if [[ "${pulled}" -eq 0 ]]; then
  echo "Could not find Android videos dir. Tried:"
  printf '  %s\n' "${ANDROID_VIDEOS_CANDIDATES[@]}"
  echo "Also check SyncService app-external path via adb if needed."
  exit 1
fi

# Optional sidecars
adb pull /sdcard/kidvid/manifest.json "${OUT_DIR}/manifest.json" 2>/dev/null || true
adb pull /sdcard/kidvid/pins.json "${OUT_DIR}/pins.json" 2>/dev/null || true

echo
echo "Export ready: ${OUT_DIR}"
echo
echo "Next (manual until iOS copy is automated):"
echo "  1. Connect iPhone to Mac, open Finder → KidVid (File Sharing)."
echo "  2. Create folder kidvid/videos/ inside the app Documents if missing."
echo "  3. Copy ${OUT_DIR}/videos/*.mp4 into kidvid/videos/."
echo "  4. Copy pins.json / manifest.json into kidvid/ if present."
echo "  5. Relaunch KidVid on the phone."
echo
echo "Later: extend this script with ifuse/idevicefs to push directly into"
echo "  <app-container>/Documents/kidvid/"
