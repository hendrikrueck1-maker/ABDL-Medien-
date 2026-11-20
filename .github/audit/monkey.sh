#!/usr/bin/env bash
# Chaos-Test: App starten, Kaltstartzeit messen, dann monkey mit festen Seeds. Ergebnis als Annotation.
set -u
LABEL="$1"
PKG=de.hr.mediathek.debug
APK=app/build/outputs/apk/debug/app-debug.apk

enc() { python3 -c 'import sys,urllib.parse;print(sys.stdin.read().replace("%","%25").replace("\r","").replace("\n","%0A"))'; }

adb install -r -g "$APK" >/dev/null
adb shell settings put global window_animation_scale 1
adb logcat -c

# Kaltstart (3x)
STARTS=""
for i in 1 2 3; do
  adb shell am force-stop $PKG
  T=$(adb shell am start -W -n $PKG/de.hr.mediathek.MainActivity | grep -E "TotalTime" | awk '{print $2}')
  STARTS="$STARTS $T"
  sleep 2
done
echo "::notice title=Kaltstart $LABEL::TotalTime ms:$STARTS"

# Rotation, Dunkelmodus, Schriftgroesse, Prozess-Tod im Hintergrund
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 2
adb shell settings put system user_rotation 0; sleep 2
adb shell cmd uimode night yes; sleep 2
adb shell settings put system font_scale 2.0; sleep 3
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am kill $PKG; sleep 1
adb shell am start -n $PKG/de.hr.mediathek.MainActivity >/dev/null; sleep 4
adb shell cmd uimode night no
adb shell settings put system font_scale 1.0

TOTAL_CRASH=0
SUMMARY=""
for SEED in ${SEEDS:-4711 1337 2026}; do
  adb shell am start -n $PKG/de.hr.mediathek.MainActivity >/dev/null; sleep 2
  OUT=$(adb shell monkey -p $PKG -s $SEED --throttle 40 --pct-syskeys 0 --pct-appswitch 2 \
        --ignore-security-exceptions -v 15000 2>&1 | tail -n 40)
  if echo "$OUT" | grep -qE "CRASH|NOT RESPONDING|Monkey aborted"; then
    TOTAL_CRASH=$((TOTAL_CRASH + 1))
    SUMMARY="$SUMMARY
--- Seed $SEED ---
$(echo "$OUT" | grep -E "CRASH|NOT RESPONDING|Exception|at de\.hr|Caused by|Long Msg|Short Msg" | head -n 25)"
  else
    SUMMARY="$SUMMARY
Seed $SEED: ohne Absturz ($(echo "$OUT" | grep -E 'Events injected' | tr -d '\r'))"
  fi
done

adb root >/dev/null 2>&1; sleep 2
ANRFILE=$(adb shell 'ls -t /data/anr/anr_* 2>/dev/null | head -n 1' | tr -d '\r')
ANRTRACE=""
if [ -n "$ANRFILE" ]; then
  ANRTRACE=$(adb shell "cat $ANRFILE" | awk '/^"main"/{f=1} f{print} /^$/{if(f)exit}' | grep -vE "^  - |^  \| " | head -n 45)
fi
STRICTTOP=$(adb logcat -d | grep -A6 "StrictMode policy violation" | grep -E "violation|at de\.hr" | sed -E 's/.*StrictMode policy violation; ~duration=[0-9]+ ms: //' | sort | uniq -c | sort -rn | head -n 12)
CRASHLOG=$(adb logcat -d -b crash 2>/dev/null | grep -E "FATAL|Exception|Error|at de\.hr|Caused by" | head -n 40)
ANRS=$(adb logcat -d | grep -E "ANR in $PKG" | head -n 5)
STRICT=$(adb logcat -d | grep -c "StrictMode" || true)

MSG="Monkey-Laeufe mit Absturz/ANR: $TOTAL_CRASH von 3
$SUMMARY
--- crash-Puffer ---
$CRASHLOG
--- ANR ---
$ANRS
StrictMode-Zeilen: $STRICT
--- StrictMode (haeufigste) ---
$STRICTTOP
--- ANR main-Thread ($ANRFILE) ---
$ANRTRACE"
LEVEL=notice
[ "$TOTAL_CRASH" -gt 0 ] || [ -n "$CRASHLOG" ] && LEVEL=error
echo "::$LEVEL title=Monkey $LABEL::$(printf '%s' "$MSG" | head -c 9000 | enc)"
exit 0
