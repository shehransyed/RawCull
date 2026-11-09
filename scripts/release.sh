#!/usr/bin/env bash
# Build a signed release APK into dist/release/. See docs/RELEASING.md.
#   bash scripts/release.sh
# Runs scripts/check.sh first; refuses to build without a signing key.
set -euo pipefail

cd "$(dirname "$0")/.." || exit 2

if [ ! -f keystore.properties ] && [ -z "${KEYSTORE_PATH:-}" ]; then
  echo "No signing key: create one with 'bash scripts/new-release-key.sh' (see docs/RELEASING.md)." >&2
  exit 1
fi

# The default Android Studio JBR on this machine is incomplete; pick one that works (as in check.sh).
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

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)
if [ -z "$VERSION" ]; then echo "Couldn't read versionName from app/build.gradle.kts." >&2; exit 1; fi
echo "Releasing RawCull $VERSION"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Warning: the working tree has uncommitted changes; the APK won't match a commit." >&2
fi

echo "1/3 Unit tests and debug build"
bash scripts/check.sh

echo "2/3 Release build"
"$GRADLE" -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
  echo "No signed APK at $APK (only an unsigned one?). Check keystore.properties." >&2
  exit 1
fi

echo "3/3 Signature"
SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | sed 's/\\:/:/g; s/\\\\/\//g')
SDK_DIR=${SDK_DIR:-${ANDROID_HOME:-}}
BUILD_TOOLS=$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS/apksigner"
if [ -f "$APKSIGNER.bat" ]; then APKSIGNER="$APKSIGNER.bat"; fi
if [ -n "$BUILD_TOOLS" ] && [ -f "$APKSIGNER" ]; then
  CERTS=$("$APKSIGNER" verify --print-certs "$APK")
  echo "$CERTS" | grep -E 'certificate (DN|SHA-256 digest)' | head -2
  if echo "$CERTS" | grep -q 'CN=Android Debug'; then
    echo "This APK is signed with the debug key. Not publishing it." >&2
    exit 1
  fi
else
  echo "apksigner not found under $SDK_DIR/build-tools; skipped the signature check." >&2
fi

mkdir -p dist/release
NAME="RawCull-$VERSION.apk"
cp "$APK" "dist/release/$NAME"
(cd dist/release && sha256sum "$NAME" > "$NAME.sha256")

echo
echo "Done: dist/release/$NAME"
echo "      dist/release/$NAME.sha256"
echo "Attach both to a GitHub release tagged v$VERSION."
