#!/system/bin/sh
# KidVid volume policy enforcer. No on-phone toggle by design.
# Holds STREAM_MUSIC at the index computed from the app's saved policy
# (kidvid_volume_prefs.xml). Falls back to 25 day / 12 night if prefs
# are missing or unreadable. Hardware volume keys stay disabled via
# the module keylayouts.
MODDIR=${0%/*}
LOG=/data/adb/kidvid_volume.log
PREFS=/data/data/com.kidvid/shared_prefs/kidvid_volume_prefs.xml

# shellcheck disable=SC1091
if [ -f "$MODDIR/volume_policy.sh" ]; then
    . "$MODDIR/volume_policy.sh"
else
    echo "$(date '+%F %T') missing volume_policy.sh" >> $LOG
    exit 0
fi

log(){
    echo "$(date '+%F %T') $*" >> $LOG
    _sz=$(stat -c %s $LOG 2>/dev/null || echo 0)
    [ "$_sz" -gt 100000 ] && mv -f $LOG $LOG.1
}

while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 5
# keep OTA path off (reversible: pm enable ...)
settings put global ota_disable_automatic_update 1
for c in SystemUpdateService SystemUpdateGcmTaskService SystemUpdatePersistentListenerService SystemUpdateActivity; do
  pm disable --user 0 com.google.android.gms/.update.$c >/dev/null 2>&1
done
log "start pid $$ policy=app-prefs"

get_stream() {
    cmd audio get-stream-volume 3 2>/dev/null | sed -n 's/.*-> \([0-9][0-9]*\).*/\1/p' | head -1
}

get_stream_max() {
    _m=$(cmd audio get-stream-max-volume 3 2>/dev/null | sed -n 's/.*\([0-9][0-9]*\).*/\1/p' | head -1)
    if [ -n "$_m" ]; then
        printf '%s\n' "$_m"
    else
        printf '25\n'
    fi
}

while :; do
  [ -f $MODDIR/disable ] || [ -f $MODDIR/remove ] && { log "module disabled, exit"; exit 0; }

  STREAM_MAX=$(get_stream_max)
  STREAM_MAX=${STREAM_MAX:-25}
  if [ -s "$PREFS" ] && grep -q 'name="vol_' "$PREFS" 2>/dev/null; then
      kidvid_load_policy "$PREFS"
  else
      kidvid_policy_reset_defaults
  fi
  MINUTE=$(kidvid_minute_now_et)
  T=$(kidvid_target_index "$MINUTE" "$STREAM_MAX")
  C=$(get_stream)
  if [ -n "$C" ] && [ "$C" != "$T" ]; then
    cmd audio set-volume 3 $T >/dev/null 2>&1
    log "min=$MINUTE vol $C -> $T prefs=$KIDVID_PREFS_LOADED"
  fi
  sleep 2
done
