#!/bin/bash
# Publish KidVid library files (mp4 and optional matching jpg) to Google Drive,
# then ask Hetzner to pull them into the shared library.
# Usage: publish-to-drive.sh FILE [FILE ...]
# Env: KIDVID_DRIVE_REMOTE (default gdrive:KidVid/720)
#      KIDVID_HETZNER_SSH  (default: ssh -o BatchMode=yes root@100.117.218.37)
set -euo pipefail
export PATH="/opt/homebrew/bin:$HOME/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
DRIVE="${KIDVID_DRIVE_REMOTE:-gdrive:KidVid/720}"
SYNC_REMOTE="${KIDVID_SYNC_REMOTE:-/root/kidvid-server/sync-from-drive.sh}"

if [[ -n "${KIDVID_HETZNER_SSH:-}" ]]; then
  SSH_CMD="$KIDVID_HETZNER_SSH"
elif ssh -o BatchMode=yes -o ConnectTimeout=8 hetzner "true" >/dev/null 2>&1; then
  SSH_CMD="ssh -o BatchMode=yes -o ConnectTimeout=30 hetzner"
else
  SSH_CMD="ssh -o BatchMode=yes -o ConnectTimeout=30 root@100.117.218.37"
fi

if [[ $# -lt 1 ]]; then
  echo "usage: $0 FILE [FILE ...]" >&2
  exit 2
fi

copied=0
for f in "$@"; do
  if [[ ! -f "$f" ]]; then
    echo "MISSING $f" >&2
    exit 1
  fi
  base="$(basename "$f")"
  case "$base" in
    *.mp4|*.MP4|*.jpg|*.JPG|*.jpeg|*.JPEG) ;;
    *) echo "SKIP $base (not mp4/jpg)" >&2; continue ;;
  esac
  echo "DRIVE_PUT $base -> $DRIVE"
  rclone copyto "$f" "$DRIVE/$base" --retries 5
  copied=$((copied+1))
  # matching sidecar jpg if we were given an mp4
  if [[ "$base" == *.mp4 || "$base" == *.MP4 ]]; then
    stem="${f%.*}"
    for ext in jpg JPG jpeg JPEG; do
      if [[ -f "$stem.$ext" ]]; then
        echo "DRIVE_PUT $(basename "$stem.$ext") -> $DRIVE"
        rclone copyto "$stem.$ext" "$DRIVE/$(basename "$stem.$ext")" --retries 5
        copied=$((copied+1))
        break
      fi
    done
  fi
done

if [[ "$copied" -eq 0 ]]; then
  echo "nothing copied" >&2
  exit 1
fi

echo "HETZNER_PULL $SYNC_REMOTE"
# word-split the ssh command on purpose so a custom prefix can include flags
# shellcheck disable=SC2086
if $SSH_CMD "$SYNC_REMOTE"; then
  echo "OK published $copied file(s)"
else
  echo "Drive copy succeeded but Hetzner pull failed; it will retry on the 15-minute timer" >&2
  exit 0
fi
