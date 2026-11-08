#!/usr/bin/env bash
# Quiet build-and-test check.
#   bash scripts/check.sh [extra gradle args]   e.g. --tests "*LibraryViewModelTest*"
# Default tasks: testDebugUnitTest assembleDebug. Full output goes to build/check.log.
# Passing: prints one summary line. Failing: prints failed tests (or compile errors),
# at most 60 lines, then the log path. The Gradle exit code is passed through.
set -u

cd "$(dirname "$0")/.." || exit 2
LOG=build/check.log
MAX_LINES=60
mkdir -p build

# The default Android Studio JBR on this machine is incomplete; pick one that works.
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

# Extra args go right after testDebugUnitTest so a task option like --tests binds to it.
TASKS=(testDebugUnitTest "$@" assembleDebug)
RESULTS=app/build/test-results
MARKER=build/.check-start
touch "$MARKER"
start=$SECONDS

"$GRADLE" -q --console=plain "${TASKS[@]}" >"$LOG" 2>&1
status=$?

elapsed=$((SECONDS - start))
duration="$((elapsed / 60))m$(printf '%02d' $((elapsed % 60)))s"

# Sums tests/failures/errors/skipped over the given JUnit XML files.
count_results() {
  awk '
    /<testsuite / {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^tests="/)    { v = $i; gsub(/[^0-9]/, "", v); t += v }
        if ($i ~ /^failures="/) { v = $i; gsub(/[^0-9]/, "", v); f += v }
        if ($i ~ /^errors="/)   { v = $i; gsub(/[^0-9]/, "", v); f += v }
        if ($i ~ /^skipped="/)  { v = $i; gsub(/[^0-9]/, "", v); s += v }
      }
    }
    END { printf "%d %d %d\n", t, f, s }
  ' "$@"
}

if [ $status -eq 0 ]; then
  mapfile -t xmls < <(find "$RESULTS" -name 'TEST-*.xml' 2>/dev/null)
  if [ ${#xmls[@]} -eq 0 ]; then
    echo "OK  no test results  ($duration)"
  else
    read -r tests failed skipped < <(count_results "${xmls[@]}")
    echo "OK  $tests tests, $failed failed, $skipped skipped  ($duration)"
  fi
  rm -f "$MARKER"
  exit 0
fi

{
  echo "FAILED  (exit $status, $duration)"
  if grep -q '^e: ' "$LOG"; then
    # Kotlin compile errors.
    grep '^e: ' "$LOG" | sed -E 's#file://[^ ]*/(app/src/)#\1#'
  else
    mapfile -t fresh < <(find "$RESULTS" -name 'TEST-*.xml' -newer "$MARKER" 2>/dev/null)
    failures=""
    if [ ${#fresh[@]} -gt 0 ]; then
      # Failed test names with the first ~10 lines of each failure.
      failures=$(awk '
        /<testcase / {
          match($0, /name="[^"]*"/); name = substr($0, RSTART + 6, RLENGTH - 7)
          match($0, /classname="[^"]*"/); cls = substr($0, RSTART + 11, RLENGTH - 12)
        }
        /<(failure|error)[ >]/ {
          print "--- " cls "." name
          line = $0; sub(/.*<(failure|error)[^>]*>/, "", line)
          n = 0; infail = 1
          if (line != "") { print "    " line; n++ }
          if ($0 ~ /<\/(failure|error)>/) infail = 0
          next
        }
        infail {
          if ($0 ~ /<\/(failure|error)>/) { infail = 0; next }
          if (n < 10) { print "    " $0; n++ }
        }
      ' "${fresh[@]}" | sed 's/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g; s/&apos;/'"'"'/g; s/&amp;/\&/g')
    fi
    if [ -n "$failures" ]; then
      read -r tests failed skipped < <(count_results "${fresh[@]}")
      echo "$tests tests, $failed failed, $skipped skipped"
      echo "$failures"
    else
      # Anything else: Gradle's own explanation.
      sed -n '/^\* What went wrong:/,/^\* Try:/p' "$LOG" | grep -v '^\* Try:' | grep -v '^$'
      grep -q '^\* What went wrong:' "$LOG" || tail -n 20 "$LOG"
    fi
  fi
} | head -n $((MAX_LINES - 1))
echo "full log: $LOG"
rm -f "$MARKER"
exit $status
