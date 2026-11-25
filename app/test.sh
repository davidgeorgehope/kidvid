#!/bin/bash
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJECT/bin/test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"

JUNIT_JAR=""
for candidate in \
    /usr/share/java/junit4.jar \
    /usr/share/java/junit-4.13.2.jar
do
    if [[ -f "$candidate" ]]; then
        JUNIT_JAR="$candidate"
        break
    fi
done
if [[ -z "$JUNIT_JAR" ]]; then
    echo "ERROR: junit4.jar not found under /usr/share/java" >&2
    exit 1
fi

HAMCREST_JAR=""
for candidate in \
    /usr/share/java/hamcrest-core.jar \
    /usr/share/java/hamcrest-2.2.jar \
    /usr/share/java/hamcrest.jar
do
    if [[ -f "$candidate" ]]; then
        HAMCREST_JAR="$candidate"
        break
    fi
done

CP="$JUNIT_JAR"
if [[ -n "$HAMCREST_JAR" ]]; then
    CP="$CP:$HAMCREST_JAR"
fi

javac -source 8 -target 8 -cp "$CP" -d "$OUT" \
    "$PROJECT/src/com/kidvid/VolumePolicy.java" \
    "$PROJECT/src/com/kidvid/VolumeSettingsStore.java" \
    "$PROJECT/src/com/kidvid/VolumeController.java" \
    "$PROJECT/src/com/kidvid/VideoMigration.java" \
    "$PROJECT/src/com/kidvid/StickyReturnPolicy.java" \
    "$PROJECT/test/com/kidvid/KidVidVolumeTest.java" \
    "$PROJECT/test/com/kidvid/KidVidMigrationTest.java" \
    "$PROJECT/test/com/kidvid/KidVidStickyReturnTest.java"

java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
    com.kidvid.KidVidVolumeTest \
    com.kidvid.KidVidMigrationTest \
    com.kidvid.KidVidStickyReturnTest
