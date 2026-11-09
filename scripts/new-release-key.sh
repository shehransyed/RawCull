#!/usr/bin/env bash
# One-time: create the release signing key and keystore.properties. See docs/RELEASING.md.
#   bash scripts/new-release-key.sh [path/to/rawcull-release.jks]
# The default location is ~/keys/rawcull-release.jks, outside the repo. Back the file up.
set -euo pipefail

cd "$(dirname "$0")/.." || exit 2
KEYSTORE="${1:-$HOME/keys/rawcull-release.jks}"
ALIAS=rawcull

if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists. Not overwriting a signing key." >&2
  exit 1
fi
if [ -e keystore.properties ]; then
  echo "keystore.properties already exists. Remove it first if you really want a new key." >&2
  exit 1
fi

# Same JDK search as check.sh: Android Studio's bundled JBR has keytool.
if [ -z "${JAVA_HOME:-}" ]; then
  for jbr in "C:/Program Files/Android/Android Studio1/jbr" "C:/Program Files/Android/Android Studio/jbr" \
             "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [ -f "$jbr/lib/jvm.cfg" ]; then export JAVA_HOME="$jbr"; break; fi
  done
fi
KEYTOOL=keytool
if [ -n "${JAVA_HOME:-}" ]; then KEYTOOL="$JAVA_HOME/bin/keytool"; fi

read -r -s -p "Choose a keystore password (at least 6 characters, no backslashes): " PASS; echo
read -r -s -p "Repeat it: " PASS2; echo
if [ "$PASS" != "$PASS2" ]; then echo "The passwords don't match." >&2; exit 1; fi
if [ "${#PASS}" -lt 6 ]; then echo "The password is too short." >&2; exit 1; fi
read -r -p "Your name, as it should appear in the certificate: " NAME

mkdir -p "$(dirname "$KEYSTORE")"
"$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=$NAME, O=RawCull" -storepass "$PASS" -keypass "$PASS"

# Forward slashes keep Gradle happy on Windows too.
STORE_PATH="$(cd "$(dirname "$KEYSTORE")" && pwd -W 2>/dev/null || pwd)/$(basename "$KEYSTORE")"
cat > keystore.properties <<EOF
# Release signing for RawCull. Git-ignored: never commit this file.
storeFile=$STORE_PATH
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

echo
echo "Created $KEYSTORE and keystore.properties."
echo "Back up the .jks file AND its password now (password manager plus a second copy)."
echo "Without both, you can never publish an update that installs over this release."
