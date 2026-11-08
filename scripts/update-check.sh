#!/usr/bin/env bash
# Update check (R8 D16): does a library made by an older qa build survive installing this one?
#   bash scripts/update-check.sh <old-qa.apk>        e.g. dist/qa/rawcull-qa-0.10.0.apk
# Only the qa app (dev.shehran.rawcull.qa) is touched; it's uninstalled first, because Room can't
# open a newer catalog with an older build. That also drops its shared folder grant, which
# device-test.sh asks for again. Needs old qa builds from 0.10.0 on (they have QaStateActivity).
set -u

cd "$(dirname "$0")/.." || exit 2
# shellcheck source=device-lib.sh
. scripts/device-lib.sh

OLD_APK=${1:-}
[ -f "$OLD_APK" ] || { echo "usage: bash scripts/update-check.sh <old-qa.apk>"; exit 2; }
LOG=build/update-check.log
mkdir -p build
: >"$LOG"
STATE_ACTIVITY=$QA_APP/dev.shehran.rawcull.qa.QaStateActivity

pick_device || exit 2
echo "device: $(device_line)" >>"$LOG"

if ! "$GRADLE" -q --console=plain assembleQa >>"$LOG" 2>&1; then
  echo "FAILED  build"; grep '^e: ' "$LOG" | head -n 40; echo "log: $LOG"; exit 1
fi

# Runs QaStateActivity with cmd $1 and prints the state file once it says done or failed.
run_state() {
  adb_ shell am start -W -n "$STATE_ACTIVITY" --es cmd "$1" >>"$LOG" 2>&1
  local i state
  for i in $(seq 1 150); do
    state=$(adb_text run-as "$QA_APP" cat files/qa-state.txt)
    case "$state" in status=done*|status=failed*) printf '%s\n' "$state"; return 0 ;; esac
    sleep 1
  done
  echo "status=timeout"
}

install_fresh() {
  local out
  out=$(adb_ install -r -d -t "$1" 2>&1)
  printf '%s\n' "$out" >>"$LOG"
  printf '%s' "$out" | grep -q '^Success' || { echo "Install of $1 failed: $(printf '%s' "$out" | grep -E 'Failure|failed' | head -n 1)"; exit 1; }
  allow_activity_starts
}

adb_ uninstall "$QA_APP" >>"$LOG" 2>&1
rm -f build/device-installed-*-"$QA_APP".txt   # device-test.sh must reinstall the current build
install_fresh "$OLD_APK"
before=$(run_state seed)
printf '%s\n' "--- seeded by $OLD_APK" "$before" >>"$LOG"
case "$before" in status=done*) ;; *) echo "FAILED  seeding with the old build: $(printf '%s' "$before" | head -n 2 | tr '\n' ' ')"; echo "log: $LOG"; exit 1 ;; esac

install_fresh "$APP_APK"
after=$(run_state dump)
printf '%s\n' "--- after installing $APP_APK" "$after" >>"$LOG"

strip() { printf '%s\n' "$1" | grep -v '^#'; }
old_version=$(printf '%s\n' "$before" | sed -n 's/^# version=//p')
new_version=$(printf '%s\n' "$after" | sed -n 's/^# version=//p')
if diff <(strip "$before") <(strip "$after") >build/update-check.diff; then
  echo "OK  $old_version -> $new_version: $(strip "$after" | grep -c '^photo ') photos, ratings, flags and settings kept"
  exit 0
fi
echo "FAILED  $old_version -> $new_version changed the library:"
head -n 50 build/update-check.diff
echo "log: $LOG"
exit 1
