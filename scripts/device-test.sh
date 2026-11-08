#!/usr/bin/env bash
# On-device test suite (R8 D13). Git Bash on Windows, or any bash.
#   bash scripts/device-test.sh [smoke|full|<class pattern>]      default: smoke
#   SAMPLES="/sdcard/..." overrides the samples folder; ANDROID_SERIAL picks a device.
# Installs only the qa app (dev.shehran.rawcull.qa) and its tests, with -r, and leaves them
# installed; the tester's own app is never touched. Writes build/device-report.md and prints one
# line when everything passes, else at most 60 lines of failures. Exit code: 0 only when every test
# that ran passed (known issues and skips don't count) and at least one test ran.
set -u

cd "$(dirname "$0")/.." || exit 2
# shellcheck source=device-lib.sh
. scripts/device-lib.sh

MODE=${1:-smoke}
LOG=build/device-test.log
RAW=build/device-instrument.txt
RESULTS=build/device-results.tsv
REPORT=build/device-report.md
SCREENS=build/device-screens
BENCH=build/device-bench
MAX_LINES=60
GRANT_CLASS=dev.shehran.rawcull.device.setup.SharedFolderGrantTest
SAMPLES_DIR=${SAMPLES:-/sdcard/Download/Quick Share/Sample Import}
mkdir -p build
: >"$LOG"
start=$SECONDS

fail() { echo "$1"; exit 2; }

pick_device || exit 2
DEVICE=$(device_line)
echo "device: $DEVICE" >>"$LOG"

sample_count=$(adb_text "ls -1 '$SAMPLES_DIR' 2>/dev/null | wc -l" | tr -d ' ')
if [ "${sample_count:-0}" -gt 0 ]; then
  SAMPLES_NOTE="$sample_count files in \`$SAMPLES_DIR\`"
else
  SAMPLES_NOTE="**missing:** \`$SAMPLES_DIR\` (tests that need samples skip)"
fi

# --- Which tests -------------------------------------------------------------------------------
case "$MODE" in
  smoke) FILTER="-e annotation dev.shehran.rawcull.device.Smoke" ;;
  full) FILTER="" ;;
  *)
    classes=$(find app/src/androidTest/java -name "*${MODE}*.kt" | sed 's#^app/src/androidTest/java/##; s#\.kt$##; s#/#.#g' | paste -sd, -)
    [ -n "$classes" ] || fail "No device test file matches '*${MODE}*.kt' under app/src/androidTest/java"
    FILTER="-e class $classes"
    ;;
esac

# --- Build, archive, install -------------------------------------------------------------------
if ! "$GRADLE" -q --console=plain assembleQa assembleQaAndroidTest >>"$LOG" 2>&1; then
  echo "FAILED  build"
  { grep '^e: ' "$LOG" | sed -E 's#file://[^ ]*/(app/src/)#\1#'; sed -n '/^\* What went wrong:/,/^\* Try:/p' "$LOG" | grep -v '^\* Try:'; } | head -n $((MAX_LINES - 2))
  echo "log: $LOG"; exit 1
fi
version=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)
mkdir -p dist/qa && cp "$APP_APK" "dist/qa/rawcull-qa-$version.apk"   # R8 D16: each round's qa APK

install_if_changed "$APP_APK" "$QA_APP" || exit 1
install_if_changed "$TEST_APK" "$QA_TEST" || exit 1
allow_activity_starts
ensure_unlocked || exit 2

# --- Shared folder grant (D9 b), then the suite ------------------------------------------------
MSYS_NO_PATHCONV=1 timeout 120 "$ADB" shell am instrument -w -r -e class "$GRANT_CLASS" "$QA_RUNNER" >build/device-grant.txt 2>&1 \
  || adb_ shell am force-stop "$QA_APP"
if grep -q 'OK (1 test)' build/device-grant.txt; then
  GRANT_NOTE="granted"
else
  GRANT_NOTE='**not granted**: on the tablet open "QA folder grant" (RawCull QA) and pick Download/RawCullQA'
fi

adb_ shell run-as "$QA_APP" rm -rf files/device-out >/dev/null 2>&1
SAMPLES_ARG="-e samples '$SAMPLES_DIR'"
# One string, so the device shell sees the quotes around the samples path.
case "$MODE" in smoke) limit=600 ;; *) limit=${DEVICE_TEST_TIMEOUT:-2400} ;; esac
MSYS_NO_PATHCONV=1 timeout "$limit" "$ADB" shell "am instrument -w -r $FILTER -e notClass $GRANT_CLASS $SAMPLES_ARG $QA_RUNNER" >"$RAW" 2>&1
if [ $? -eq 124 ]; then
  adb_ shell am force-stop "$QA_APP"
  locked=""; adb_text dumpsys deviceidle | grep -q 'mScreenLocked=true' && locked=" The tablet locked during the run."
  echo "INSTRUMENTATION_RESULT: shortMsg=Timed out after ${limit}s.$locked" >>"$RAW"
fi
cat "$RAW" >>"$LOG"

# --- Parse `am instrument -r` ------------------------------------------------------------------
# One line per finished test: code \x1f class \x1f test \x1f stack (lines joined by \x1e).
# Codes: 0 pass, -1 error, -2 failure, -3 ignored, -4 assumption (skip or known issue).
awk '
  /^INSTRUMENTATION_STATUS: / {
    kv = substr($0, 25); k = kv; sub(/=.*/, "", k); v = substr(kv, length(k) + 2)
    key = k; val[k] = v; next
  }
  /^INSTRUMENTATION_STATUS_CODE: / {
    code = substr($0, 30) + 0
    if (code == 1) { started = val["class"] "\x1f" val["test"]; delete val; key = ""; next }
    printf "%d\x1f%s\x1f%s\x1f%s\n", code, val["class"], val["test"], val["stack"]
    started = ""; delete val; key = ""; next
  }
  /^INSTRUMENTATION_RESULT: (shortMsg|longMsg)=/ { crash = crash substr($0, 25) " "; next }
  /^INSTRUMENTATION_(RESULT|CODE|FAILED)/ { key = ""; next }
  { if (key == "stack") val["stack"] = val["stack"] "\x1e" $0 }
  END {
    if (started != "") printf "-1\x1f%s\x1fProcess crashed: %s\n", started, crash
    else if (crash != "") printf "-1\x1f(run)\x1f(instrumentation)\x1fProcess crashed: %s\n", crash
  }
' "$RAW" >"$RESULTS"

count() { awk -F'\x1f' -v c="$1" '$1 == c' "$RESULTS" | wc -l | tr -d ' '; }
passed=$(count 0)
failed=$(( $(count -1) + $(count -2) ))
known=$(awk -F'\x1f' '$1 == -4 && index($4, "KNOWN ISSUE")' "$RESULTS" | wc -l | tr -d ' ')
skipped=$(( $(count -4) + $(count -3) - known ))
total=$(( passed + failed + skipped + known ))

# --- Pull screenshots and benchmark numbers ----------------------------------------------------
rm -rf build/device-out "$SCREENS" && mkdir -p "$SCREENS" "$BENCH"
adb_ exec-out run-as "$QA_APP" tar -cf - -C files device-out 2>/dev/null | tar -xf - -C build 2>/dev/null
[ -d build/device-out/screens ] && cp -r build/device-out/screens/. "$SCREENS"/
bench_now=$(cat build/device-out/bench/*.json 2>/dev/null | grep -oE '"[^"]+"[[:space:]]*:[[:space:]]*[0-9.]+' | sed -E 's/"([^"]+)"[[:space:]]*:[[:space:]]*/\1 /')

elapsed=$((SECONDS - start))
duration="$((elapsed / 60))m$(printf '%02d' $((elapsed % 60)))s"

# --- Report ------------------------------------------------------------------------------------
{
  echo "# Device test report"
  echo
  echo "- **Run:** \`$MODE\`, $(date '+%Y-%m-%d %H:%M'), $duration"
  echo "- **Device:** $DEVICE"
  echo "- **App:** $QA_APP $version (the tester's app isn't installed over)"
  echo "- **Samples:** $SAMPLES_NOTE"
  echo "- **Shared folder:** $GRANT_NOTE"
  echo "- **Result:** $passed passed, $failed failed, $skipped skipped, $known known issues ($total tests)"
  echo
  echo "## By area"
  echo
  echo "| Area | Passed | Failed | Skipped | Known |"
  echo "|---|---:|---:|---:|---:|"
  awk -F'\x1f' '
    {
      area = $2
      if (match(area, /\.device\.[a-z]+\./)) area = substr(area, RSTART + 8, RLENGTH - 9)
      else { sub(/^dev\.shehran\.rawcull\./, "", area); sub(/\.[A-Z][A-Za-z0-9_]*$/, "", area); area = "other: " area }
      areas[area] = 1
      if ($1 == 0) p[area]++
      else if ($1 == -1 || $1 == -2) f[area]++
      else if ($1 == -4 && index($4, "KNOWN ISSUE")) k[area]++
      else s[area]++
    }
    END { for (a in areas) printf "| %s | %d | %d | %d | %d |\n", a, p[a], f[a], s[a], k[a] }
  ' "$RESULTS" | sort
  echo
  echo "## Failures"
  echo
  if [ "$failed" -eq 0 ]; then echo "None."; else
    awk -F'\x1f' '$1 == -1 || $1 == -2 {
      printf "### %s.%s\n\n```\n", $2, $3
      n = split($4, lines, "\x1e"); shown = 0
      for (i = 1; i <= n && shown < 15; i++) if (lines[i] != "") { print lines[i]; shown++ }
      print "```\n"
    }' "$RESULTS"
  fi
  echo
  echo "## Known issues"
  echo
  if [ "$known" -eq 0 ]; then echo "None failed."; else
    awk -F'\x1f' '$1 == -4 && index($4, "KNOWN ISSUE") {
      split($4, lines, "\x1e"); line = lines[1]; sub(/^.*KNOWN ISSUE /, "", line)
      printf "- `%s.%s`: %s\n", $2, $3, line
    }' "$RESULTS"
  fi
  if [ "$skipped" -gt 0 ]; then
    echo
    echo "## Skipped"
    echo
    awk -F'\x1f' '($1 == -3 || $1 == -4) && !index($4, "KNOWN ISSUE") {
      split($4, lines, "\x1e"); line = lines[1]; sub(/^[^:]*Exception: /, "", line)
      printf "- `%s.%s`: %s\n", $2, $3, line
    }' "$RESULTS"
  fi
  echo
  echo "## Benchmark"
  echo
  if [ -z "$bench_now" ]; then echo "No numbers this run."; else
    echo "Debuggable build: read trends, not absolutes. ⚠ marks > 2× the last run."
    echo
    echo "| Metric | Now (ms) | Last (ms) | Ratio |"
    echo "|---|---:|---:|---:|"
    last=$(grep -oE '"[^"]+"[[:space:]]*:[[:space:]]*[0-9.]+' "$BENCH/last.json" 2>/dev/null | sed -E 's/"([^"]+)"[[:space:]]*:[[:space:]]*/\1 /')
    printf '%s\n' "$bench_now" | while read -r name now; do
      before=$(printf '%s\n' "$last" | awk -v n="$name" '$1 == n { print $2; exit }')
      awk -v n="$name" -v a="$now" -v b="$before" 'BEGIN {
        if (b == "" || b == 0) { printf "| %s | %s | – | – |\n", n, a; exit }
        r = a / b; printf "| %s | %s | %s | %.2f%s |\n", n, a, b, r, (r > 2 ? " ⚠" : "")
      }'
    done
  fi
  echo
  echo "## Screenshots"
  echo
  shots=$(find "$SCREENS" -type f -name '*.png' | sort)
  if [ -z "$shots" ]; then echo "None this run."; else printf '%s\n' "$shots" | sed 's/^/- /'; fi
} >"$REPORT"

# Keep this run's numbers as the next run's "last".
if [ -n "$bench_now" ]; then
  printf '%s\n' "$bench_now" | awk 'BEGIN { printf "{" } { printf "%s\"%s\": %s", (NR > 1 ? ", " : ""), $1, $2 } END { print "}" }' >"$BENCH/last.json"
fi

# --- Console -----------------------------------------------------------------------------------
summary="$MODE: $passed passed, $failed failed, $skipped skipped, $known known  ($duration)"
if [ "$failed" -eq 0 ] && [ "$total" -gt 0 ]; then
  echo "OK  $summary  report: $REPORT"
  case "$GRANT_NOTE" in granted) ;; *) echo "note: shared folder not granted; see $REPORT" ;; esac
  exit 0
fi
{
  echo "FAILED  $summary"
  [ "$total" -eq 0 ] && { echo "No test ran."; grep -E '^INSTRUMENTATION_(RESULT|FAILED)|^Error|Exception' "$RAW" | head -n 10; }
  awk -F'\x1f' '$1 == -1 || $1 == -2 {
    printf "--- %s.%s\n", $2, $3
    n = split($4, lines, "\x1e"); shown = 0
    for (i = 1; i <= n && shown < 8; i++) if (lines[i] != "") { print "    " lines[i]; shown++ }
  }' "$RESULTS"
} | head -n $((MAX_LINES - 1))
echo "report: $REPORT"
exit 1
