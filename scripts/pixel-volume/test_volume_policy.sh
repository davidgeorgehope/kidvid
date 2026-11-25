#!/bin/sh
# Behavior tests for volume_policy.sh. Run from repo root or this directory.
set -eu
HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$HERE/volume_policy.sh"

fail=0
assert_eq() {
    _got=$1
    _want=$2
    _msg=$3
    if [ "$_got" != "$_want" ]; then
        echo "FAIL: $_msg (got=$_got want=$_want)"
        fail=$((fail + 1))
    fi
}

kidvid_policy_reset_defaults
assert_eq "$(kidvid_target_percent $((7 * 60)))" 100 "default 07:00 is day 100"
assert_eq "$(kidvid_target_percent $((21 * 60 + 59)))" 100 "default 21:59 is day 100"
assert_eq "$(kidvid_target_percent $((22 * 60)))" 50 "default 22:00 is dip 50"
assert_eq "$(kidvid_target_percent 0)" 50 "default midnight is dip 50"
assert_eq "$(kidvid_target_percent $((6 * 60 + 59)))" 50 "default 06:59 is dip 50"
assert_eq "$(kidvid_target_index $((7 * 60)) 25)" 25 "default day index 25"
assert_eq "$(kidvid_target_index $((22 * 60)) 25)" 12 "default night index is floor(0.5*25)=12"

# Prefs: parent raises night volume to 80, max 90, dip starts 21:00 for 3h
cat > /tmp/kidvid_prefs_test.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="vol_starting_pct" value="100" />
    <int name="vol_max_pct" value="90" />
    <boolean name="vol_dip_enabled" value="true" />
    <int name="vol_dip_start_min" value="1260" />
    <int name="vol_dip_duration_min" value="180" />
    <int name="vol_dip_pct" value="80" />
</map>
XML
kidvid_load_policy /tmp/kidvid_prefs_test.xml
assert_eq "$KIDVID_PREFS_LOADED" 1 "prefs loaded"
assert_eq "$(kidvid_target_percent $((20 * 60 + 59)))" 90 "before 21:00 capped at max 90"
assert_eq "$(kidvid_target_percent $((21 * 60)))" 80 "21:00 dip 80"
assert_eq "$(kidvid_target_percent 0)" 90 "after 3h window, midnight is starting capped 90"
assert_eq "$(kidvid_target_index $((21 * 60)) 25)" 20 "floor(0.8*25)=20"
assert_eq "$(kidvid_target_index $((12 * 60)) 25)" 22 "floor(0.9*25)=22"

# Missing / unreadable prefs fall back to legacy defaults
kidvid_load_policy /tmp/does-not-exist-kidvid-prefs.xml
assert_eq "$KIDVID_PREFS_LOADED" 0 "missing prefs not marked loaded"
assert_eq "$(kidvid_target_index $((22 * 60)) 25)" 12 "missing prefs night 12"
assert_eq "$(kidvid_target_index $((10 * 60)) 25)" 25 "missing prefs day 25"

kidvid_load_policy "-" <<'XML'
not xml at all
XML
assert_eq "$(kidvid_target_index $((22 * 60)) 25)" 12 "garbage xml falls back to 12"

# Dip disabled
kidvid_load_policy "-" <<'XML'
<map>
    <int name="vol_starting_pct" value="70" />
    <int name="vol_max_pct" value="70" />
    <boolean name="vol_dip_enabled" value="false" />
    <int name="vol_dip_start_min" value="1320" />
    <int name="vol_dip_duration_min" value="540" />
    <int name="vol_dip_pct" value="20" />
</map>
XML
assert_eq "$(kidvid_target_percent $((23 * 60)))" 70 "disabled dip stays at starting"
assert_eq "$(kidvid_target_index $((23 * 60)) 25)" 17 "floor(0.7*25)=17"

if [ "$fail" -eq 0 ]; then
    echo "OK: volume_policy.sh tests passed"
    exit 0
fi
echo "FAILED: $fail assertion(s)"
exit 1
