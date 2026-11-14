#!/bin/bash
# Pull the KidVid Drive master (gdrive:KidVid/720) into the Hetzner shared library.
# Idempotent. Copies only missing top-level *.mp4 / *.jpg. Never deletes, never
# touches acks.json / deletes.json / nox-home.json / phone|fire|pixel|iphone subdirs.
# Same-name files already on disk with a different size are left alone and logged
# (set KIDVID_REPLACE=1 to overwrite).
#
# Install: copy this script to /root/kidvid-server/sync-from-drive.sh and enable
# video-server/kidvid-sync-from-drive.timer.
set -euo pipefail
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
DRIVE="${KIDVID_DRIVE_REMOTE:-gdrive:KidVid/720}"
DEST="${KIDVID_DIR:-/root/kidvid-server/videos}"
REPLACE="${KIDVID_REPLACE:-0}"
OWNER="${KIDVID_OWNER:-svc-kidvid:svc-kidvid}"
LIST=$(mktemp)
TO_COPY=$(mktemp)
trap 'rm -f "$LIST" "$TO_COPY"' EXIT

mkdir -p "$DEST"
# p=path s=size; paths are basenames because the Drive folder is flat
if ! rclone lsf --format "ps" --separator $'\t' --include "*.mp4" --include "*.jpg" --include "*.MP4" --include "*.JPG" "$DRIVE" >"$LIST"; then
  echo "rclone lsf failed for $DRIVE" >&2
  exit 1
fi

copied=0
skipped=0
conflict=0
while IFS=$'\t' read -r name size; do
  [[ -z "${name:-}" ]] && continue
  # never write into a subdir, never take a Drive file that looks like state
  case "$name" in
    */*) echo "SKIP nested $name"; skipped=$((skipped+1)); continue ;;
    acks.json|deletes.json|nox-home.json|*.json) echo "SKIP state $name"; skipped=$((skipped+1)); continue ;;
  esac
  dest="$DEST/$name"
  if [[ -f "$dest" ]]; then
    have=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest")
    if [[ "$have" == "$size" ]]; then
      skipped=$((skipped+1))
      continue
    fi
    if [[ "$REPLACE" != "1" ]]; then
      echo "CONFLICT $name drive=$size dest=$have (left in place; KIDVID_REPLACE=1 to overwrite)"
      conflict=$((conflict+1))
      continue
    fi
    echo "REPLACE $name dest=$have -> drive=$size"
  else
    echo "COPY $name ($size bytes)"
  fi
  printf '%s\n' "$name" >>"$TO_COPY"
  copied=$((copied+1))
done <"$LIST"

if [[ -s "$TO_COPY" ]]; then
  rclone copy "$DRIVE" "$DEST" --files-from-raw "$TO_COPY" --no-traverse --retries 5
  # ownership for the service user; kidvid-perms.timer is the backstop
  if [[ -n "$OWNER" ]] && id "${OWNER%%:*}" >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    xargs -a "$TO_COPY" -I{} chown "$OWNER" "$DEST/{}" 2>/dev/null || true
    xargs -a "$TO_COPY" -I{} chmod 644 "$DEST/{}" 2>/dev/null || true
  fi
fi

drive_n=$(grep -c . "$LIST" || true)
echo "DONE drive=$drive_n copy=$copied skip=$skipped conflict=$conflict dest=$DEST"
