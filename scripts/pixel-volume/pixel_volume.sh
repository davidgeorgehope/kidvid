#!/bin/bash
# Mini backup enforcer for Pixel KidVid volume.
# Reads the app's volume policy (same math as Magisk kidvid_lock) and
# sets STREAM_MUSIC to that index. Falls back to 25 day / 12 night if
# prefs are missing. Uses platform-tools adb 37+; Homebrew cask 36.0.2
# fails `adb connect` with "No route to host" even when TCP 5555 is open.

set -u
DIR=$HOME/.config/nox
mkdir -p "$DIR"
LOG=$DIR/pixel_volume.log
IPF=$DIR/pixel_ip
ERRF=$DIR/pixel_volume.last-adb

# Shared policy (installed next to this script, or from the kidvid checkout).
POLICY=
for cand in \
    "$DIR/volume_policy.sh" \
    "$(CDPATH= cd -- "$(dirname "$0")" && pwd)/volume_policy.sh" \
    "$HOME/workspace/kidvid/scripts/pixel-volume/volume_policy.sh"
do
    if [ -f "$cand" ]; then
        POLICY=$cand
        break
    fi
done
if [ -z "$POLICY" ]; then
    echo "$(date '+%F %T') missing volume_policy.sh" >> "$LOG"
    exit 0
fi
# shellcheck disable=SC1090
. "$POLICY"

# Prefer Android commandlinetools platform-tools (37.0.1+). The Homebrew
# cask at /opt/homebrew/bin/adb (36.0.2) cannot connect to this Pixel.
pick_adb() {
    local c ver major
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
        # Remember a fallback in case nothing is 37+
        if [ -z "${ADB_FALLBACK:-}" ]; then
            ADB_FALLBACK=$c
        fi
    done
    if [ -n "${ADB_FALLBACK:-}" ]; then
        printf '%s\n' "$ADB_FALLBACK"
        return 0
    fi
    return 1
}

ADB=$(pick_adb || true)
if [ -z "${ADB:-}" ]; then
    echo "$(date '+%F %T') no adb binary found" >> "$LOG"
    exit 0
fi

# Reliable timeout: perl alarm can leave adb 36 wedged; kill the process group.
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

log() {
    echo "$(date '+%F %T') $*" >> "$LOG"
    local s
    s=$(stat -f%z "$LOG" 2>/dev/null || stat -c%s "$LOG" 2>/dev/null || echo 0)
    [ "$s" -gt 200000 ] && mv "$LOG" "$LOG.1"
}

IP=$(cat "$IPF" 2>/dev/null || true)
IP=${IP:-192.168.87.26}
SERIAL="$IP:5555"

adb_out() {
    run_timeout "$1" "$ADB" "${@:2}" 2>"$ERRF"
}

# Wake a dozing Pixel: connect can need several tries (200-1200 ms pings).
ensure_connected() {
    local tries=${1:-5} i out rc
    out=$(adb_out 8 devices) || true
    if printf '%s\n' "$out" | grep -q "^${SERIAL}[[:space:]]*device"; then
        return 0
    fi
    i=0
    while [ "$i" -lt "$tries" ]; do
        out=$(adb_out 15 connect "$SERIAL") || true
        if printf '%s\n' "$out" | grep -qiE 'connected to|already connected'; then
            out=$(adb_out 8 devices) || true
            if printf '%s\n' "$out" | grep -q "^${SERIAL}[[:space:]]*device"; then
                return 0
            fi
        fi
        i=$((i + 1))
        sleep 2
    done
    return 1
}

discover_ip() {
    local nip
    nip=$(arp -a 2>/dev/null | sed -n 's/^pixel-6a[^(]*(\([0-9.]*\)).*/\1/p' | head -1)
    if [ -z "$nip" ]; then
        nip=$("$ADB" mdns services 2>/dev/null | grep -o '192\.168\.[0-9.]*' | head -1 || true)
    fi
    if [ -n "$nip" ] && [ "$nip" != "$IP" ]; then
        IP=$nip
        SERIAL="$IP:5555"
        echo "$IP" > "$IPF"
        log "ip -> $IP"
        return 0
    fi
    return 1
}

if ! ensure_connected 5; then
    if discover_ip; then
        ensure_connected 3 || true
    fi
fi

if ! adb_out 8 devices 2>/dev/null | grep -q "^${SERIAL}[[:space:]]*device"; then
    err=$(tr '\n' ' ' < "$ERRF" 2>/dev/null | sed 's/[[:space:]]*$//')
    log "unreachable $IP adb=$ADB err=${err:-no-output}"
    exit 0
fi

get_stream() {
    # Typical: "STREAM_MUSIC index:17 -> 25 [25]" or similar. Take the current value after "-> ".
    adb_out 12 -s "$SERIAL" shell cmd audio get-stream-volume 3 2>/dev/null | sed -n 's/.*-> \([0-9][0-9]*\).*/\1/p' | head -1
}

get_stream_max() {
    local m
    m=$(adb_out 12 -s "$SERIAL" shell cmd audio get-stream-max-volume 3 2>/dev/null | sed -n 's/.*\([0-9][0-9]*\).*/\1/p' | head -1)
    if [ -n "${m:-}" ]; then
        printf '%s\n' "$m"
        return
    fi
    # Fallback: parse dumpsys or assume Pixel STREAM_MUSIC max.
    printf '25\n'
}

# Prefs require su; the app is not debuggable.
read_prefs() {
    adb_out 20 -s "$SERIAL" shell su -c 'cat /data/data/com.kidvid/shared_prefs/kidvid_volume_prefs.xml' 2>/dev/null || true
}

STREAM_MAX=$(get_stream_max)
STREAM_MAX=${STREAM_MAX:-25}
PREFS_XML=$(read_prefs)
if printf '%s\n' "$PREFS_XML" | grep -q 'name="vol_'; then
    kidvid_load_policy - <<EOF
$PREFS_XML
EOF
else
    kidvid_policy_reset_defaults
fi

MINUTE=$(kidvid_minute_now_et)
TARGET=$(kidvid_target_index "$MINUTE" "$STREAM_MAX")
CURRENT=$(get_stream)

if [ -z "${CURRENT:-}" ]; then
    err=$(tr '\n' ' ' < "$ERRF" 2>/dev/null | sed 's/[[:space:]]*$//')
    log "reachable but no volume read $IP err=${err:-no-output}"
    exit 0
fi

if [ "$CURRENT" != "$TARGET" ]; then
    adb_out 12 -s "$SERIAL" shell cmd audio set-volume 3 "$TARGET" >/dev/null 2>&1 || true
    NOW=$(get_stream)
    log "min=$MINUTE max=$STREAM_MAX vol $CURRENT -> $TARGET (now ${NOW:-?}) prefs=$KIDVID_PREFS_LOADED"
fi
