#!/usr/bin/env bash
# Shared by device-test.sh and update-check.sh (R8 D13, D16). Source it; it defines:
#   GRADLE, ADB, adb_ (adb without Git Bash path mangling), pick_device, device_line,
#   install_if_changed <apk> <package>, allow_activity_starts.

QA_APP=dev.shehran.rawcull.qa
QA_TEST=$QA_APP.test
QA_RUNNER=$QA_TEST/androidx.test.runner.AndroidJUnitRunner
APP_APK=app/build/outputs/apk/qa/app-qa.apk
TEST_APK=app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk

# The default Android Studio JBR on this machine is incomplete; pick one that works (as check.sh).
if [ -z "${JAVA_HOME:-}" ]; then
  for jbr in "C:/Program Files/Android/Android Studio1/jbr" "C:/Program Files/Android/Android Studio/jbr" \
             "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [ -f "$jbr/lib/jvm.cfg" ]; then export JAVA_HOME="$jbr"; break; fi
  done
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) GRADLE=./gradlew.bat ;;
  *) GRADLE=./gradlew ;;
esac

ADB=""
sdk_dir=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | sed 's#\\:#:#g; s#\\\\#/#g')
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$sdk_dir" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Library/Android/sdk"; do
  [ -n "$candidate" ] || continue
  for exe in "$candidate/platform-tools/adb.exe" "$candidate/platform-tools/adb"; do
    if [ -x "$exe" ]; then ADB=$exe; break 2; fi
  done
done
[ -n "$ADB" ] || ADB=$(command -v adb || true)

# Git Bash rewrites /sdcard/... into Windows paths unless told not to; only adb needs that.
adb_() { MSYS_NO_PATHCONV=1 "$ADB" "$@"; }

# Device shell output ends lines with \r on some builds.
adb_text() { adb_ shell "$@" 2>/dev/null | tr -d '\r'; }

# Sets ANDROID_SERIAL to the one device, or explains and returns 2. The same tablet over USB and
# over wireless debugging counts once (USB wins).
pick_device() {
  if [ -z "$ADB" ]; then echo "adb not found: set ANDROID_HOME or sdk.dir in local.properties"; return 2; fi
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    if ! adb_ -s "$ANDROID_SERIAL" get-state >/dev/null 2>&1; then echo "ANDROID_SERIAL=$ANDROID_SERIAL isn't connected"; return 2; fi
    export ANDROID_SERIAL; return 0
  fi
  declare -A by_hw=()
  local serial hw
  while read -r serial; do
    [ -n "$serial" ] || continue
    hw=$(adb_ -s "$serial" shell getprop ro.serialno 2>/dev/null | tr -d '\r')
    [ -n "$hw" ] || hw=$serial
    case "$serial" in
      *:*|*._adb-tls-connect.*) [ -n "${by_hw[$hw]:-}" ] || by_hw[$hw]=$serial ;;
      *) by_hw[$hw]=$serial ;;
    esac
  done < <(adb_ devices | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }')
  case ${#by_hw[@]} in
    0) echo "No device. Plug in the tablet with USB debugging on and accept this PC's key."; return 2 ;;
    1) ANDROID_SERIAL=${by_hw[*]}; export ANDROID_SERIAL; return 0 ;;
    *) echo "${#by_hw[@]} devices; set ANDROID_SERIAL to one of: ${by_hw[*]}"; return 2 ;;
  esac
}

# "Xiaomi 21051182G (nabu), Android 13 / API 33, serial 1bd884e0"
device_line() {
  echo "$(adb_text getprop ro.product.manufacturer) $(adb_text getprop ro.product.model) ($(adb_text getprop ro.product.device)), Android $(adb_text getprop ro.build.version.release) / API $(adb_text getprop ro.build.version.sdk), serial $ANDROID_SERIAL"
}

# Installs <apk> with -r (data and grants stay) unless that exact APK is already on the device.
# MIUI asks on the tablet for every USB install, so skipping unchanged ones saves taps.
install_if_changed() {
  local apk=$1 package=$2 stamp hash
  stamp="build/device-installed-${ANDROID_SERIAL//[^A-Za-z0-9]/_}-$package.txt"
  hash=$(sha256sum "$apk" | cut -d' ' -f1)
  if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$hash" ] && adb_text pm path "$package" | grep -q '^package:'; then
    return 0
  fi
  local out
  out=$(adb_ install -r -t "$apk" 2>&1)
  if ! printf '%s' "$out" | grep -q '^Success'; then
    echo "Install of $package failed: $(printf '%s' "$out" | grep -E 'Failure|failed' | head -n 1)"
    case "$out" in
      *INSTALL_FAILED_USER_RESTRICTED*)
        echo "MIUI asks on the tablet before each USB install: tap Install there, or turn on"
        echo "Developer options -> Install via USB, then run this again." ;;
    esac
    return 1
  fi
  echo "$hash" >"$stamp"
}

# MIUI blocks an app from starting its own activities from the background (op 10021) until allowed;
# the instrumented app does exactly that. Elsewhere the op doesn't exist and this does nothing.
allow_activity_starts() {
  adb_ shell appops set "$QA_APP" 10021 allow >/dev/null 2>&1 || true
}

# Activities can't start behind the lock screen, and a locked run just hangs. Wakes the screen,
# dismisses a lock without a PIN, and otherwise asks for the tablet to be unlocked (returns 2).
ensure_unlocked() {
  adb_ shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  adb_ shell wm dismiss-keyguard >/dev/null 2>&1
  sleep 1
  if adb_text dumpsys deviceidle | grep -q 'mScreenLocked=true'; then
    echo "The tablet is locked: unlock it and leave it awake during the run, then run this again."
    return 2
  fi
}
