#!/bin/zsh
# End-to-end KidVid iOS library push. Runs on the Mac mini; survives chat disconnects.
# Usage:
#   ./push-library-to-ios.sh red
#   ./push-library-to-ios.sh yellow
#   ./push-library-to-ios.sh silver
#   ./push-library-to-ios.sh red --verify-only
# Env overrides: STAGE_PARENT, BUNDLE, STATUS_DIR, TIMEOUT_SEC

set -euo pipefail
export PATH="/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

PHONE="${1:-}"
MODE="${2:-}"
BUNDLE="${BUNDLE:-com.kidvid.ios}"
STAGE_PARENT="${STAGE_PARENT:-/tmp/kidvid-stage-pixel-full}"
STATUS_DIR="${STATUS_DIR:-/tmp/kidvid-ios-sync}"
TIMEOUT_SEC="${TIMEOUT_SEC:-14400}"
mkdir -p "$STATUS_DIR"

case "$PHONE" in
  red|Red)
    UDID="${UDID:-00008110-001C60163CD0201E}"
    LABEL=red
    ;;
  yellow|Yellow)
    UDID="${UDID:-00008110-000579922179A01E}"
    LABEL=yellow
    ;;
  silver|Silver)
    UDID="${UDID:-00008120-0014709A26F0E01E}"
    LABEL=silver
    ;;
  *)
    echo "usage: $0 red|yellow|silver [--verify-only]" >&2
    exit 2
    ;;
esac

STATUS="$STATUS_DIR/${LABEL}.status.json"
LOG="$STATUS_DIR/${LABEL}.log"
LOCK="$STATUS_DIR/${LABEL}.lock"

log() { print -r -- "$(date '+%Y-%m-%d %H:%M:%S%z') $*" | tee -a "$LOG" }
write_status() {
  local state="$1" detail="${2:-}"
  /usr/bin/python3 - "$STATUS" "$state" "$detail" "$LABEL" "$UDID" <<'PY'
import json,sys,time
path,state,detail,label,udid=sys.argv[1:6]
data={"ts":time.time(),"label":label,"udid":udid,"state":state,"detail":detail}
json.dump(data, open(path,"w"), indent=2)
print(path)
PY
}

count_library() {
  local out="$STATUS_DIR/${LABEL}.files.txt"
  xcrun devicectl device info files \
    --device "$UDID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE" \
    >"$out" 2>&1 || true
  /usr/bin/python3 - "$out" <<'PY'
import re,sys
text=open(sys.argv[1]).read()
mp4=re.findall(r'Documents/kidvid/videos/[^/\s]+\.mp4', text, re.I)
zeros=len(re.findall(r'Documents/kidvid/videos/[^\n]*Zero KB', text))
wrong=len(re.findall(r'Documents/videos/[^/\s]+\.mp4', text, re.I))
pins=1 if 'Documents/kidvid/pins.json' in text else 0
manifest=1 if 'Documents/kidvid/manifest.json' in text else 0
print(f"mp4={len(mp4)} zero={zeros} wrong_path={wrong} pins={pins} manifest={manifest}")
PY
}

verify_ok() {
  local counts
  counts="$(count_library)"
  log "verify $counts"
  /usr/bin/python3 - "$counts" <<'PY'
import sys
# mp4=N zero=N wrong_path=N pins=N manifest=N
parts=dict(p.split('=',1) for p in sys.argv[1].split())
mp4=int(parts.get('mp4',0)); zero=int(parts.get('zero',0))
pins=int(parts.get('pins',0)); manifest=int(parts.get('manifest',0))
# success: >=90 real files, no zeros left, sidecars present
sys.exit(0 if mp4>=90 and zero==0 and pins and manifest else 1)
PY
}

launch_app() {
  log "launch $BUNDLE"
  xcrun devicectl device process launch --device "$UDID" "$BUNDLE" 2>&1 | tee -a "$LOG" \
}

if [[ "$MODE" == "--verify-only" ]]; then
  write_status verifying
  if verify_ok; then
    launch_app
    write_status ok "verify-only passed"
    log DONE_OK
    exit 0
  else
    write_status failed "verify-only failed: $(count_library)"
    exit 1
  fi
fi

# exclusive lock so chat reconnects don't double-start
if [[ -f "$LOCK" ]]; then
  oldpid=$(cat "$LOCK" 2>/dev/null || true)
  if [[ -n "$oldpid" ]] && kill -0 "$oldpid" 2>/dev/null; then
    log "another sync running pid=$oldpid — exit"
    write_status busy "pid=$oldpid"
    exit 0
  fi
fi
echo $$ >"$LOCK"
trap 'rm -f "$LOCK"' EXIT

# Stage 480p on demand from the single library (KIDVID_SELECT = names file/dir, default all).
if [[ -z "${KEEP_STAGE:-}" ]]; then
  STAGE_PARENT="$(mktemp -d /tmp/kidvid-stage-${LABEL}.XXXX)"
  trap 'rm -f "$LOCK"; rm -rf "$STAGE_PARENT"' EXIT
  write_status staging "select=${KIDVID_SELECT:-all}"
  "$(dirname "$0")/stage-480.sh" "$STAGE_PARENT" "${KIDVID_SELECT:-}" >>"$LOG" 2>&1 || log "stage warnings"
fi
if [[ ! -d "$STAGE_PARENT/kidvid/videos" ]]; then
  write_status failed "missing stage $STAGE_PARENT/kidvid/videos"
  exit 1
fi
expected=$(find "$STAGE_PARENT/kidvid/videos" -iname '*.mp4' | wc -l | tr -d ' ')
log "start push label=$LABEL udid=$UDID expected_mp4=$expected stage=$STAGE_PARENT"
write_status copying "expected=$expected"

# Parent folder copy preserves Documents/kidvid/... (do NOT copy kidvid contents flat into Documents)
xcrun devicectl device copy to \
  --device "$UDID" \
  --domain-type appDataContainer \
  --domain-identifier "$BUNDLE" \
  --source "$STAGE_PARENT" \
  --destination Documents \
  --remove-existing-content true \
  --timeout "$TIMEOUT_SEC" \
  --verbose \
  --json-output "$STATUS_DIR/${LABEL}.copy.json" \
  2>&1 | tee -a "$LOG"
copy_rc=${pipestatus[1]:-$?}
log "copy_rc=$copy_rc"
write_status verifying "copy_rc=$copy_rc"

# poll until zeros clear or timeout (devicectl sometimes returns before FS settles)
deadline=$(( $(date +%s) + 600 ))
while true; do
  if verify_ok; then
    launch_app
    write_status ok "$(count_library)"
    log DONE_OK
    exit 0
  fi
  if (( $(date +%s) >= deadline )); then
    write_status failed "$(count_library)"
    log DONE_FAIL
    exit 1
  fi
  sleep 15
done
