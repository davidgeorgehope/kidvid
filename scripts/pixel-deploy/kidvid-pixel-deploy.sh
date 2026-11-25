#!/bin/bash
# One-shot Pixel deploy for KidVid 1.7.4-auth (or any newer APK already
# staged at ~/workspace/kidvid/app/kidvid.apk). Waits until the Pixel is
# reachable AND idle, then: adb install -r, update Magisk kidvid_lock,
# verify version + volume target, poke hetzner-event-poke, and disarm.
#
# Never interrupts a playing video. Never downgrades. Never prints the
# webhook key. Expires after 7 days if the phone never becomes idle.

set -u
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:/opt/homebrew/bin:/usr/bin:/bin:$PATH"

REPO="${KIDVID_REPO:-$HOME/workspace/kidvid}"
APK="${KIDVID_APK:-$REPO/app/kidvid.apk}"
STATE="${KIDVID_DEPLOY_STATE:-$HOME/.config/kidvid-pixel-deploy}"
LOG="$STATE/deploy.log"
IPF="${PIXEL_IP_FILE:-$HOME/.config/nox/pixel_ip}"
DEFAULT_IP=192.168.87.26
JOB_NAME=kidvid-pixel-deploy
EXPIRY_DAYS=7
mkdir -p "$STATE"

log() {
    echo "$(date '+%F %T %Z') $*" | tee -a "$LOG" >/dev/null
    # also echo to stdout for launchd stdout path
    echo "$(date '+%F %T %Z') $*"
}

# Already finished or cancelled.
if [ -f "$STATE/disarmed" ]; then
    exit 0
fi

# First run stamp
if [ ! -f "$STATE/started_at" ]; then
    date +%s > "$STATE/started_at"
    log "watcher started, will wait up to ${EXPIRY_DAYS}d for an idle Pixel"
fi

STARTED=$(cat "$STATE/started_at" 2>/dev/null || date +%s)
NOW=$(date +%s)
if [ $((NOW - STARTED)) -gt $((EXPIRY_DAYS * 86400)) ]; then
    log "expired after ${EXPIRY_DAYS} days without a successful deploy"
    # fall through to webhook + disarm at the end via report
    EXPIRED=1
else
    EXPIRED=0
fi

pick_adb() {
    local c ver major fallback=
    for c in \
        /opt/homebrew/share/android-commandlinetools/platform-tools/adb \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "${ANDROID_HOME:-}/platform-tools/adb" \
        /opt/homebrew/bin/adb \
        "$(command -v adb 2>/dev/null || true)"
    do
        [ -n "$c" ] && [ -x "$c" ] || continue
        ver=$("$c" version 2>/dev/null || true)
        # "Android Debug Bridge version 1.0.41" is the protocol; the
        # tool release is on the following line: "Version 37.0.1-...".
        major=$(printf '%s\n' "$ver" | sed -n 's/^Version \([0-9][0-9]*\).*/\1/p' | head -1)
        if [ -n "$major" ] && [ "$major" -ge 37 ]; then
            printf '%s\n' "$c"
            return 0
        fi
        [ -z "$fallback" ] && fallback=$c
    done
    [ -n "$fallback" ] && { printf '%s\n' "$fallback"; return 0; }
    return 1
}

pick_aapt() {
    local d
    for d in \
        /opt/homebrew/share/android-commandlinetools/build-tools/* \
        "${ANDROID_SDK_ROOT:-}/build-tools/"* \
        "${ANDROID_HOME:-}/build-tools/"*
    do
        if [ -x "$d/aapt" ]; then
            printf '%s\n' "$d/aapt"
            return 0
        fi
    done
    return 1
}

run_timeout() {
    local secs=$1
    shift
    python3 -c '
import os, signal, subprocess, sys
secs=int(sys.argv[1])
args=sys.argv[2:]
p=subprocess.Popen(args, preexec_fn=os.setsid, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
try:
    out, err = p.communicate(timeout=secs)
except subprocess.TimeoutExpired:
    try:
        os.killpg(p.pid, signal.SIGKILL)
    except OSError:
        pass
    out, err = p.communicate()
    sys.stderr.buffer.write(err or b"")
    sys.exit(124)
sys.stdout.buffer.write(out or b"")
sys.stderr.buffer.write(err or b"")
sys.exit(p.returncode)
' "$secs" "$@"
}

disarm() {
    date +%s > "$STATE/disarmed"
    log "disarmed"
    # Best-effort: do not fail the script if we are the running agent.
    launchctl bootout "gui/$(id -u)/com.kidvid.pixel-deploy" >/dev/null 2>&1 || true
}

# Source webhook env without ever printing it.
# Official Sonic Hetzner event-poke routine. Never use JOB_WEBHOOK_URL/KEY.
poke() {
    local status=$1 rc=$2 detail=$3
    unset JOB_WEBHOOK_URL JOB_WEBHOOK_KEY
    local ENVF="$HOME/.config/mini-usb-pipeline-webhook.env"
    if [ -f "$ENVF" ]; then
        set -a
        # shellcheck disable=SC1090
        . "$ENVF"
        set +a
    fi
    local POKE_URL="${POKE_URL:-}"
    local POKE_KEY="${POKE_KEY:-${HETZNER_POKE_WEBHOOK_KEY:-}}"
    if [ -z "$POKE_URL" ] || [ -z "$POKE_KEY" ]; then
        log "webhook skipped (no config) status=$status rc=$rc"
        unset POKE_URL POKE_KEY HETZNER_POKE_WEBHOOK_KEY
        return 0
    fi
    # Keep the payload in env vars so the key is never on a command line or in the log.
    export _KV_STATUS="$status" _KV_RC="$rc" _KV_DETAIL="$detail" _KV_JOB="$JOB_NAME"
    local payload http
    payload=$(/usr/bin/python3 -c 'import json,os; print(json.dumps({"event":"job","job":os.environ["_KV_JOB"],"status":os.environ["_KV_STATUS"],"rc":int(os.environ["_KV_RC"]),"detail":os.environ["_KV_DETAIL"]}))')
    http=$(curl -s -m 20 -o "$STATE/webhook.body" -w "%{http_code}" -X POST \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $POKE_KEY" \
        -d "$payload" \
        "$POKE_URL" || echo curlfail)
    log "webhook status=$status rc=$rc http=$http"
    unset POKE_URL POKE_KEY HETZNER_POKE_WEBHOOK_KEY _KV_STATUS _KV_RC _KV_DETAIL _KV_JOB payload
}

finish_fail() {
    local rc=$1
    local detail=$2
    poke failed "$rc" "$detail"
    disarm
    exit 0
}

finish_ok() {
    local detail=$1
    poke done 0 "$detail"
    disarm
    exit 0
}

if [ "$EXPIRED" = "1" ]; then
    finish_fail 1 "expired after ${EXPIRY_DAYS} days waiting for reachable idle Pixel"
fi

if [ ! -f "$APK" ]; then
    log "no apk at $APK (will retry)"
    exit 0
fi

ADB=$(pick_adb || true)
AAPT=$(pick_aapt || true)
if [ -z "${ADB:-}" ]; then
    log "no adb (will retry)"
    exit 0
fi

APK_CODE=
APK_NAME=
if [ -n "${AAPT:-}" ]; then
    APK_CODE=$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
    APK_NAME=$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
fi
if [ -z "${APK_CODE:-}" ]; then
    log "cannot read apk version (will retry)"
    exit 0
fi

# Hard guard: never install a downgrade of the live 1.7.3-auth (versionCode 12) tree.
# A compatible APK must be newer than what is on the device; we also refuse anything
# below 13 so the previously staged 1.1 (versionCode 2) cannot be pushed by accident.
if [ "$APK_CODE" -lt 13 ]; then
    finish_fail 2 "refusing apk versionCode=$APK_CODE versionName=$APK_NAME (need >= 13 / 1.7.4-auth or newer; do not install 1.1 over 1.7.3-auth)"
fi

IP=$(cat "$IPF" 2>/dev/null || true)
IP=${IP:-$DEFAULT_IP}
SERIAL="$IP:5555"

ensure_connected() {
    local tries=${1:-6} i out
    out=$(run_timeout 8 "$ADB" devices) || true
    if printf '%s\n' "$out" | grep -q "^${SERIAL}[[:space:]]*device"; then
        return 0
    fi
    i=0
    while [ "$i" -lt "$tries" ]; do
        out=$(run_timeout 20 "$ADB" connect "$SERIAL") || true
        if printf '%s\n' "$out" | grep -qiE 'connected to|already connected'; then
            out=$(run_timeout 8 "$ADB" devices) || true
            if printf '%s\n' "$out" | grep -q "^${SERIAL}[[:space:]]*device"; then
                return 0
            fi
        fi
        i=$((i + 1))
        sleep 2
    done
    return 1
}

if ! ensure_connected 6; then
    log "pixel unreachable $IP (will retry, doze wake can take a few tries)"
    exit 0
fi

SHELLC() {
    run_timeout "${1:-20}" "$ADB" -s "$SERIAL" shell "${@:2}"
}

# After adb install -r: disable keyguard, make KidVid HOME, start it,
# re-enter lock task, and fail if a screen off/on cycle shows keyguard.
relaunch_kiosk() {
    local out state task home was_awake disp win kg focus
    was_awake=0
    disp=$(SHELLC 15 dumpsys display 2>/dev/null | sed -n 's/^[[:space:]]*Display State=\([A-Z]*\).*/\1/p' | head -1)
    [ "$disp" = "ON" ] && was_awake=1

    run_timeout 20 "$ADB" -s "$SERIAL" shell su -c "locksettings set-disabled true" >/dev/null 2>&1 || true
    run_timeout 20 "$ADB" -s "$SERIAL" shell cmd package set-home-activity com.kidvid/.MainActivity >/dev/null 2>&1 || true
    out=$(SHELLC 30 am start -W -n com.kidvid/.MainActivity -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1) || true
    log "kiosk start: $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-240)"
    sleep 2
    state=$(SHELLC 20 dumpsys activity activities 2>/dev/null | sed -n 's/.*mLockTaskModeState=\([A-Z]*\).*/\1/p' | head -1)
    if [ "$state" != "LOCKED" ]; then
        task=$(SHELLC 20 dumpsys activity activities 2>/dev/null | sed -n 's/.*Task{[^ ]* #\([0-9][0-9]*\) type=standard A=[0-9]*:com.kidvid.*/\1/p' | head -1)
        if [ -n "$task" ]; then
            SHELLC 15 am task lock "$task" >/dev/null 2>&1 || true
            sleep 1
            state=$(SHELLC 20 dumpsys activity activities 2>/dev/null | sed -n 's/.*mLockTaskModeState=\([A-Z]*\).*/\1/p' | head -1)
        fi
    fi
    if [ "$state" != "LOCKED" ]; then
        SHELLC 30 am start -n com.kidvid/.MainActivity >/dev/null 2>&1 || true
        sleep 2
        state=$(SHELLC 20 dumpsys activity activities 2>/dev/null | sed -n 's/.*mLockTaskModeState=\([A-Z]*\).*/\1/p' | head -1)
    fi
    home=$(SHELLC 15 cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1)
    log "kiosk lock=$state home=$(printf '%s' "$home" | tr '\n' ' ')"
    if [ "$state" != "LOCKED" ]; then
        return 1
    fi

    # Regression check: cycle screen OFF -> ON and ensure KidVid is focused with no keyguard.
    disp=$(SHELLC 15 dumpsys display 2>/dev/null | sed -n 's/^[[:space:]]*Display State=\([A-Z]*\).*/\1/p' | head -1)
    if [ "$disp" = "ON" ]; then
        SHELLC 15 input keyevent 26 >/dev/null 2>&1 || true
        sleep 2
    fi
    SHELLC 15 input keyevent 26 >/dev/null 2>&1 || true
    sleep 2
    win=$(SHELLC 20 dumpsys window 2>/dev/null || true)
    kg=$(printf '%s\n' "$win" | sed -n 's/.*isKeyguardShowing=\([a-z]*\).*/\1/p' | head -1)
    focus=$(printf '%s\n' "$win" | sed -n 's/.*mCurrentFocus=Window{[^}]* \([^}]*\)}.*/\1/p' | head -1)
    log "kiosk screen-cycle keyguard=${kg:-?} focus=${focus:-?}"
    if [ "$was_awake" = "0" ]; then
        SHELLC 15 input keyevent 26 >/dev/null 2>&1 || true
    fi
    if [ "$kg" = "true" ] || ! printf '%s' "${focus:-}" | grep -q "com.kidvid"; then
        return 1
    fi
    return 0
}

# Idle = screen off AND KidVid is not in a started / PLAYING media state.
# MediaPlayer construct/release with state:idle is not treated as playing.
# Android 15 dumpsys power no longer prints mScreenState; use mWakefulness
# plus dumpsys display ("Display State=OFF" / "mState=OFF").
is_idle() {
    local power display session audio
    power=$(SHELLC 20 dumpsys power 2>/dev/null || true)
    display=$(SHELLC 25 dumpsys display 2>/dev/null || true)
    if printf '%s\n' "$power" | grep -qE 'mWakefulness=(Awake|Dreaming)'; then
        return 1
    fi
    if printf '%s\n' "$power" | grep -q 'mScreenState=ON'; then
        return 1
    fi
    if printf '%s\n' "$display" | grep -qE '^[[:space:]]*Display State=ON$'; then
        return 1
    fi
    screen_off=0
    if printf '%s\n' "$power" | grep -q 'mScreenState=OFF'; then
        screen_off=1
    fi
    if printf '%s\n' "$power" | grep -qE 'mWakefulness=(Dozing|Asleep)'; then
        screen_off=1
    fi
    if printf '%s\n' "$display" | grep -qE '^[[:space:]]*Display State=OFF$'; then
        screen_off=1
    fi
    if printf '%s\n' "$display" | grep -qE '^[[:space:]]*mState=OFF$'; then
        screen_off=1
    fi
    if [ "$screen_off" != "1" ]; then
        return 1
    fi
    session=$(SHELLC 25 dumpsys media_session 2>/dev/null || true)
    # Look at the com.kidvid session only, and only for an active PLAYING/state=3.
    if printf '%s\n' "$session" | python3 -c '
import sys,re
text=sys.stdin.read()
# Split roughly by packages
playing=False
blocks=re.split(r"\n(?=\s*(?:Sessions Stack|package=|Dump of session))", text)
for b in blocks:
    if "com.kidvid" not in b:
        continue
    if re.search(r"state=PLAYING|state=3\b|PlaybackState\s*\{state=3", b):
        playing=True
sys.exit(0 if playing else 1)
' 2>/dev/null; then
        return 1
    fi
    audio=$(SHELLC 25 dumpsys audio 2>/dev/null || true)
    if printf '%s\n' "$audio" | python3 -c '
import sys,re
text=sys.stdin.read()
# Flag started MediaPlayer/AudioTrack attributed to com.kidvid
playing=False
# piid lines often include package and state on nearby lines
for m in re.finditer(r"com\.kidvid[\s\S]{0,400}", text):
    chunk=m.group(0)
    if re.search(r"state:started|state:started\b", chunk):
        playing=True
        break
# Also the inverse window: state:started near kidvid
for m in re.finditer(r"state:started[\s\S]{0,400}com\.kidvid", text):
    playing=True
    break
sys.exit(0 if playing else 1)
' 2>/dev/null; then
        return 1
    fi
    return 0
}

if ! is_idle; then
    log "pixel reachable but not idle (screen on or KidVid playing) — will retry"
    exit 0
fi

# Re-check idle immediately before any mutating step.
if ! is_idle; then
    log "became busy before install — will retry"
    exit 0
fi

# Installed version
PKG=$(SHELLC 20 dumpsys package com.kidvid 2>/dev/null || true)
INST_CODE=$(printf '%s\n' "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
INST_NAME=$(printf '%s\n' "$PKG" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
INST_CODE=${INST_CODE:-0}

if [ "$APK_CODE" -le "$INST_CODE" ]; then
    log "apk $APK_NAME ($APK_CODE) is not newer than installed $INST_NAME ($INST_CODE); skipping install, still updating Magisk if needed"
    SKIP_INSTALL=1
else
    SKIP_INSTALL=0
fi

if [ "$SKIP_INSTALL" = "0" ]; then
    # Final idle gate
    if ! is_idle; then
        log "became busy at install gate — will retry"
        exit 0
    fi
    log "installing $APK_NAME ($APK_CODE) over $INST_NAME ($INST_CODE)"
    INST_OUT=$(run_timeout 180 "$ADB" -s "$SERIAL" install -r "$APK" 2>&1) || true
    if ! printf '%s\n' "$INST_OUT" | grep -qiE 'Success'; then
        finish_fail 3 "adb install failed: $(printf '%s' "$INST_OUT" | tr '\n' ' ' | cut -c1-400)"
    fi
    log "install ok"
    # install -r kills the running process and ends the lock-task session.
    # BootReceiver only runs at BOOT_COMPLETED, and the HOME role can fall
    # back to Nexus Launcher after a package replace, so screen-on would
    # show the Pixel launcher instead of KidVid. Restore HOME, start the
    # app, and re-enter lock task before we leave the phone idle-but-unlocked.
    if ! relaunch_kiosk; then
        finish_fail 7 "apk installed but failed to relaunch KidVid into lock task"
    fi
fi

# Update Magisk module files (policy + service). Keylayouts unchanged.
# Live restart of the volume loop is safe: it only sets STREAM_MUSIC.
# Do not pass a multi-line script to `adb shell su -c` — Magisk su then
# sees `su -c` with no argument, the copies run unprivileged, and the
# module stays at the old hardcoded 25/12 service.sh.
MOD_SRC="$REPO/magisk/kidvid_lock"
if [ -f "$MOD_SRC/service.sh" ] && [ -f "$MOD_SRC/volume_policy.sh" ]; then
    run_timeout 30 "$ADB" -s "$SERIAL" push "$MOD_SRC/service.sh" /data/local/tmp/kidvid_service.sh >/dev/null
    run_timeout 30 "$ADB" -s "$SERIAL" push "$MOD_SRC/volume_policy.sh" /data/local/tmp/kidvid_volume_policy.sh >/dev/null
    run_timeout 30 "$ADB" -s "$SERIAL" push "$MOD_SRC/module.prop" /data/local/tmp/kidvid_module.prop >/dev/null
    cat > "$STATE/install-kidvid-lock.sh" << "EOF"
#!/system/bin/sh
set -e
mkdir -p /data/adb/modules/kidvid_lock
cp /data/local/tmp/kidvid_service.sh /data/adb/modules/kidvid_lock/service.sh
cp /data/local/tmp/kidvid_volume_policy.sh /data/adb/modules/kidvid_lock/volume_policy.sh
cp /data/local/tmp/kidvid_module.prop /data/adb/modules/kidvid_lock/module.prop
chmod 755 /data/adb/modules/kidvid_lock/service.sh
chmod 644 /data/adb/modules/kidvid_lock/volume_policy.sh /data/adb/modules/kidvid_lock/module.prop
pkill -f /data/adb/modules/kidvid_lock/service.sh || true
sleep 1
sh /data/adb/modules/kidvid_lock/service.sh >/dev/null 2>&1 &
echo OK
EOF
    run_timeout 30 "$ADB" -s "$SERIAL" push "$STATE/install-kidvid-lock.sh" /data/local/tmp/install-kidvid-lock.sh >/dev/null
    LOCK_OUT=$(run_timeout 25 "$ADB" -s "$SERIAL" shell su -c "sh /data/local/tmp/install-kidvid-lock.sh") || true
    if printf '%s\n' "$LOCK_OUT" | grep -q "OK"; then
        log "magisk kidvid_lock service updated and restarted"
    else
        log "magisk kidvid_lock update failed: $(printf '%s' "$LOCK_OUT" | tr '\n' ' ' | cut -c1-300)"
        finish_fail 6 "magisk module copy failed (su). apk may already be installed; module not updated"
    fi
else
    log "magisk sources missing under $MOD_SRC"
fi

# Verify installed version
PKG=$(SHELLC 20 dumpsys package com.kidvid 2>/dev/null || true)
NEW_CODE=$(printf '%s\n' "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
NEW_NAME=$(printf '%s\n' "$PKG" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
if [ "$SKIP_INSTALL" = "0" ]; then
    if [ "${NEW_CODE:-0}" -lt "$APK_CODE" ]; then
        finish_fail 4 "verify failed: installed versionCode=${NEW_CODE:-unset} versionName=${NEW_NAME:-unset}, expected $APK_CODE $APK_NAME"
    fi
fi

# Verify volume target matches policy (same math as the enforcers).
POLICY="$REPO/scripts/pixel-volume/volume_policy.sh"
# shellcheck disable=SC1090
. "$POLICY"
PREFS=$(SHELLC 20 su -c 'cat /data/data/com.kidvid/shared_prefs/kidvid_volume_prefs.xml' 2>/dev/null || true)
if printf '%s\n' "$PREFS" | grep -q 'name="vol_'; then
    kidvid_load_policy - <<EOF
$PREFS
EOF
else
    kidvid_policy_reset_defaults
fi
STREAM_MAX=$(SHELLC 15 cmd audio get-stream-max-volume 3 2>/dev/null | sed -n 's/.*\([0-9][0-9]*\).*/\1/p' | head -1)
STREAM_MAX=${STREAM_MAX:-25}
MINUTE=$(kidvid_minute_now_et)
WANT=$(kidvid_target_index "$MINUTE" "$STREAM_MAX")
# Give the restarted loop a moment to apply
sleep 3
HAVE=$(SHELLC 15 cmd audio get-stream-volume 3 2>/dev/null | sed -n 's/.*-> \([0-9][0-9]*\).*/\1/p' | head -1)
if [ -z "${HAVE:-}" ] || [ "$HAVE" != "$WANT" ]; then
    # One explicit set, then re-read (phone may still be settling after install).
    SHELLC 15 cmd audio set-volume 3 "$WANT" >/dev/null 2>&1 || true
    sleep 1
    HAVE=$(SHELLC 15 cmd audio get-stream-volume 3 2>/dev/null | sed -n 's/.*-> \([0-9][0-9]*\).*/\1/p' | head -1)
fi
if [ "${HAVE:-}" != "$WANT" ]; then
    finish_fail 5 "version=${NEW_NAME:-?} (${NEW_CODE:-?}) but volume have=${HAVE:-unset} want=$WANT (min=$MINUTE max=$STREAM_MAX)"
fi

DETAIL="installed ${NEW_NAME} (versionCode ${NEW_CODE}) volume ${HAVE}/${STREAM_MAX} matches policy (min=$MINUTE prefs=$KIDVID_PREFS_LOADED)"
log "success $DETAIL"
echo "$DETAIL" > "$STATE/success"
finish_ok "$DETAIL"
