#!/bin/sh
# Shared KidVid volume policy. POSIX. Sourced by Magisk service.sh and
# the mini's pixel_volume.sh. Computes one STREAM_MUSIC index from the
# app's kidvid_volume_prefs.xml (or defaults that match legacy 25/12).
#
# Defaults equal current behaviour: 100% 07:00-22:00 ET, 50% 22:00-07:00 ET.
# Index uses integer floor so 50% of Pixel max 25 is 12, not 13.

KIDVID_PREF_STARTING=100
KIDVID_PREF_MAX=100
KIDVID_PREF_DIP_ENABLED=1
KIDVID_PREF_DIP_START=1320
KIDVID_PREF_DIP_DURATION=540
KIDVID_PREF_DIP_PCT=50
KIDVID_PREFS_LOADED=0

kidvid_clamp_pct() {
    _v=$1
    [ -n "$_v" ] || _v=0
    [ "$_v" -lt 0 ] 2>/dev/null && _v=0
    [ "$_v" -gt 100 ] 2>/dev/null && _v=100
    printf '%s\n' "$_v"
}

kidvid_clamp_duration() {
    _v=$1
    [ -n "$_v" ] || _v=0
    [ "$_v" -lt 0 ] 2>/dev/null && _v=0
    [ "$_v" -gt 1440 ] 2>/dev/null && _v=1440
    printf '%s\n' "$_v"
}

kidvid_norm_minute() {
    _v=$1
    [ -n "$_v" ] || _v=0
    # Portable modulo for possibly-negative values
    _v=$((_v % 1440))
    [ "$_v" -lt 0 ] && _v=$((_v + 1440))
    printf '%s\n' "$_v"
}

kidvid_policy_reset_defaults() {
    KIDVID_PREF_STARTING=100
    KIDVID_PREF_MAX=100
    KIDVID_PREF_DIP_ENABLED=1
    KIDVID_PREF_DIP_START=1320
    KIDVID_PREF_DIP_DURATION=540
    KIDVID_PREF_DIP_PCT=50
    KIDVID_PREFS_LOADED=0
}

# Extract an Android SharedPreferences int/boolean value from XML on stdin or $1 path.
# Usage: kidvid_pref_xml_value <xml-file-or-minus> <key> <kind:int|boolean> <default>
kidvid_pref_xml_value() {
    _src=$1
    _key=$2
    _kind=$3
    _def=$4
    _xml=
    if [ "$_src" = "-" ]; then
        _xml=$(cat)
    elif [ -n "$_src" ] && [ -f "$_src" ]; then
        _xml=$(cat "$_src")
    else
        printf '%s\n' "$_def"
        return
    fi
    _line=$(printf '%s\n' "$_xml" | grep -F "name=\"$_key\"" | head -1)
    if [ -z "$_line" ]; then
        printf '%s\n' "$_def"
        return
    fi
    _val=$(printf '%s\n' "$_line" | sed -n 's/.*value="\([^"]*\)".*/\1/p')
    if [ -z "$_val" ]; then
        printf '%s\n' "$_def"
        return
    fi
    if [ "$_kind" = "boolean" ]; then
        case "$_val" in
            true|TRUE|1) printf '1\n' ;;
            false|FALSE|0) printf '0\n' ;;
            *) printf '%s\n' "$_def" ;;
        esac
        return
    fi
    # ints must be decimal
    case "$_val" in
        ''|*[!0-9-]* ) printf '%s\n' "$_def" ;;
        *) printf '%s\n' "$_val" ;;
    esac
}

# Load policy from a prefs XML file, XML text on stdin ("-"), or empty/missing
# (defaults). Returns 0 always; KIDVID_PREFS_LOADED=1 only when XML parsed.
kidvid_load_policy() {
    kidvid_policy_reset_defaults
    _src=${1:-}
    _xml=
    if [ "$_src" = "-" ]; then
        _xml=$(cat)
    elif [ -n "$_src" ] && [ -f "$_src" ]; then
        # empty or unreadable file -> defaults
        _xml=$(cat "$_src" 2>/dev/null || true)
    elif [ -n "$_src" ] && [ "$_src" != "-" ]; then
        # Raw XML snippet passed as an argument (must look like prefs).
        case "$_src" in
            *'<map'*|*'vol_'*) _xml=$_src ;;
            *) return 0 ;;
        esac
    fi
    if [ -z "$_xml" ]; then
        return 0
    fi
    if ! printf '%s\n' "$_xml" | grep -q 'name="vol_'; then
        return 0
    fi
    _s=$(printf '%s\n' "$_xml" | kidvid_pref_xml_value - vol_starting_pct int 100)
    _m=$(printf '%s\n' "$_xml" | kidvid_pref_xml_value - vol_max_pct int 100)
    _de=$(printf '%s\n' "$_xml" | kidvid_pref_xml_value - vol_dip_enabled boolean 1)
    _ds=$(printf '%s\n' "$_xml" | kidvid_pref_xml_value - vol_dip_start_min int 1320)
    _dd=$(printf '%s\n' "$_xml" | kidvid_pref_xml_value - vol_dip_duration_min int 540)
    _dp=$(printf '%s\n' "$_xml" | kidvid_pref_xml_value - vol_dip_pct int 50)
    KIDVID_PREF_STARTING=$(kidvid_clamp_pct "$_s")
    KIDVID_PREF_MAX=$(kidvid_clamp_pct "$_m")
    KIDVID_PREF_DIP_ENABLED=$_de
    KIDVID_PREF_DIP_START=$(kidvid_norm_minute "$_ds")
    KIDVID_PREF_DIP_DURATION=$(kidvid_clamp_duration "$_dd")
    KIDVID_PREF_DIP_PCT=$(kidvid_clamp_pct "$_dp")
    KIDVID_PREFS_LOADED=1
}

kidvid_is_in_dip() {
    _t=$(kidvid_norm_minute "$1")
    if [ "$KIDVID_PREF_DIP_ENABLED" != "1" ] || [ "$KIDVID_PREF_DIP_DURATION" -le 0 ]; then
        printf '0\n'
        return
    fi
    if [ "$KIDVID_PREF_DIP_DURATION" -ge 1440 ]; then
        printf '1\n'
        return
    fi
    _elapsed=$(( (_t - KIDVID_PREF_DIP_START + 1440) % 1440 ))
    if [ "$_elapsed" -lt "$KIDVID_PREF_DIP_DURATION" ]; then
        printf '1\n'
    else
        printf '0\n'
    fi
}

kidvid_target_percent() {
    _t=${1:-0}
    if [ "$(kidvid_is_in_dip "$_t")" = "1" ]; then
        _raw=$KIDVID_PREF_DIP_PCT
    else
        _raw=$KIDVID_PREF_STARTING
    fi
    if [ "$_raw" -gt "$KIDVID_PREF_MAX" ]; then
        _raw=$KIDVID_PREF_MAX
    fi
    printf '%s\n' "$_raw"
}

# floor(percent * stream_max / 100), capped by the max-percent index.
kidvid_desired_index() {
    _pct=$(kidvid_clamp_pct "${1:-0}")
    _max=${2:-25}
    [ "$_max" -gt 0 ] 2>/dev/null || { printf '0\n'; return; }
    if [ "$_pct" -gt "$KIDVID_PREF_MAX" ]; then
        _pct=$KIDVID_PREF_MAX
    fi
    _cap=$(( KIDVID_PREF_MAX * _max / 100 ))
    _idx=$(( _pct * _max / 100 ))
    [ "$_idx" -gt "$_cap" ] && _idx=$_cap
    [ "$_idx" -gt "$_max" ] && _idx=$_max
    [ "$_idx" -lt 0 ] && _idx=0
    printf '%s\n' "$_idx"
}

kidvid_target_index() {
    _t=${1:-0}
    _max=${2:-25}
    kidvid_desired_index "$(kidvid_target_percent "$_t")" "$_max"
}

kidvid_minute_now_et() {
    TZ=America/New_York date +%H:%M | awk -F: '{print ($1 * 60) + $2}'
}
