#!/bin/bash
# Optional out-of-band Fire HD hardening (requires ADB).
# Does NOT require Device Owner. Safe to re-run.
# Usage: ./scripts/fire-harden.sh [serial]
set -euo pipefail

ADB=(adb)
if [[ $# -ge 1 ]]; then
  ADB=(adb -s "$1")
fi

echo "=== Fire HD optional harden (disable Amazon clutter) ==="
echo "Device:"
"${ADB[@]}" devices -l

# Common Fire launcher / store / docs-like surfaces kids escape into.
# If a package is missing on your firmware, pm disable-user simply fails — ignore.
PACKAGES=(
  com.amazon.firelauncher
  com.amazon.kindle.otter
  com.amazon.kindle.otter.oobe
  com.amazon.venezia
  com.amazon.kindle
  com.amazon.cloud9
  com.amazon.docs
  com.amazon.photos
  com.amazon.avod
  com.amazon.mp3
  com.amazon.dee.app
  com.amazon.alexa.mode
  com.amazon.tahoe
  com.amazon.windowshop
)

for pkg in "${PACKAGES[@]}"; do
  if "${ADB[@]}" shell pm path "$pkg" >/dev/null 2>&1; then
    echo "Disabling $pkg"
    "${ADB[@]}" shell pm disable-user --user 0 "$pkg" || true
  else
    echo "Skip (not installed): $pkg"
  fi
done

echo ""
echo "Re-enable later with: adb shell pm enable <package>"
echo "KidVid soft lockdown does not need this; it is an extra belt for Fire."
