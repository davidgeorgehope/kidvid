#!/bin/bash
# Build a 480p push stage from the KidVid library (on demand).
# usage: stage-480.sh STAGE_PARENT [NAMES_FILE_OR_DIR]
#   NAMES: file with one .mp4 basename per line, or a dir whose *.mp4 names select titles.
#   Default selection: whole library.
# Encodes are cached in $CACHE keyed by name+source mtime; cache pruned to $CACHE_MAX_GB.
#
# Library lookup order for each title:
#   1. $KIDVID_LIBRARY (default ~/Movies/KidVid_720) if the file is present
#   2. otherwise rclone copy from $KIDVID_DRIVE_REMOTE (default gdrive:KidVid/720)
#      into $KIDVID_720_CACHE (default ~/Library/Caches/kidvid-720)
# After the mini library is removed, (2) is the normal path.
set -uo pipefail
export PATH=/opt/homebrew/bin:/usr/bin:/bin:$PATH
LIB="${KIDVID_LIBRARY:-$HOME/Movies/KidVid_720}"
DRIVE="${KIDVID_DRIVE_REMOTE:-gdrive:KidVid/720}"
FETCH="${KIDVID_720_CACHE:-$HOME/Library/Caches/kidvid-720}"
CACHE="${KIDVID_480_CACHE:-$HOME/Library/Caches/kidvid-480}"
CACHE_MAX_GB="${CACHE_MAX_GB:-5}"
STAGE_PARENT="${1:?stage dir}"; SEL="${2:-}"
OUT="$STAGE_PARENT/kidvid/videos"; mkdir -p "$OUT" "$CACHE"

names() {
  if [[ -n "$SEL" && -d "$SEL" ]]; then (cd "$SEL" && ls *.mp4)
  elif [[ -n "$SEL" ]]; then grep -v '^\s*$' "$SEL"
  elif [[ -d "$LIB" ]]; then (cd "$LIB" && ls *.mp4)
  else
    # Drive is the master when the local folder is gone
    rclone lsf --include "*.mp4" "$DRIVE"
  fi
}

resolve_src() {
  local n="$1"
  if [[ -f "$LIB/$n" ]]; then
    echo "$LIB/$n"
    return 0
  fi
  mkdir -p "$FETCH"
  if [[ ! -s "$FETCH/$n" ]]; then
    rclone copyto "$DRIVE/$n" "$FETCH/$n" || return 1
  fi
  [[ -s "$FETCH/$n" ]] || return 1
  echo "$FETCH/$n"
}

rc=0
while IFS= read -r n; do
  [[ -z "$n" ]] && continue
  src=$(resolve_src "$n") || { echo "MISSING $n" >&2; rc=1; continue; }
  key="$CACHE/$(stat -f%m "$src")_$n"
  if [[ ! -s "$key" ]]; then
    tmp="$CACHE/.tmp.$$.$n"
    ffmpeg -nostdin -loglevel error -y -i "$src" -vf "scale=-2:'min(480,ih)'" \
      -c:v libx264 -preset medium -crf 23 -c:a aac -b:a 96k -movflags +faststart "$tmp"
    if [[ ! -s "$tmp" ]]; then rm -f "$tmp"; echo "FAIL $n" >&2; rc=1; continue; fi
    # keep source if re-encode is not smaller
    if (( $(stat -f%z "$tmp") >= $(stat -f%z "$src") )); then rm -f "$tmp"; cp "$src" "$key"; else mv "$tmp" "$key"; fi
    find "$CACHE" -name "*_$n" ! -path "$key" -delete
  fi
  ln -f "$key" "$OUT/$n" 2>/dev/null || cp "$key" "$OUT/$n"
  echo "STAGED $n"
done < <(names)
# prune cache oldest-first above cap
while (( $(du -sk "$CACHE" | cut -f1) > CACHE_MAX_GB*1048576 )); do
  old=$(ls -tr "$CACHE" | head -1); [[ -n "$old" ]] || break; rm -f "$CACHE/$old"
done
exit $rc
