#!/bin/bash
# Build a 480p push stage from the single KidVid library (on demand).
# usage: stage-480.sh STAGE_PARENT [NAMES_FILE_OR_DIR]
#   NAMES: file with one .mp4 basename per line, or a dir whose *.mp4 names select titles.
#   Default selection: whole library.
# Encodes are cached in $CACHE keyed by name+source mtime; cache pruned to $CACHE_MAX_GB.
set -uo pipefail
export PATH=/opt/homebrew/bin:/usr/bin:/bin:$PATH
LIB="${KIDVID_LIBRARY:-$HOME/Movies/KidVid_720}"
CACHE="${KIDVID_480_CACHE:-$HOME/Library/Caches/kidvid-480}"
CACHE_MAX_GB="${CACHE_MAX_GB:-5}"
STAGE_PARENT="${1:?stage dir}"; SEL="${2:-}"
OUT="$STAGE_PARENT/kidvid/videos"; mkdir -p "$OUT" "$CACHE"
names() {
  if [[ -z "$SEL" ]]; then (cd "$LIB" && ls *.mp4)
  elif [[ -d "$SEL" ]]; then (cd "$SEL" && ls *.mp4)
  else grep -v '^\s*$' "$SEL"; fi
}
rc=0
while IFS= read -r n; do
  src="$LIB/$n"
  [[ -f "$src" ]] || { echo "MISSING $n" >&2; rc=1; continue; }
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
