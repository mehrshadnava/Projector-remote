#!/usr/bin/env bash
# Builds the APKs without Gradle or Android Studio (see README for the toolchain).
#
#   ./build.sh                    phone remote      -> phone-remote/projector-remote.apk
#   ./build.sh install            ...and install it on the USB-connected phone
#   ./build.sh helper             projector helper  -> projector-helper/projector-helper.apk
#   PROJECTOR=<ip>:5555 ./build.sh helper install   ...and install it on the projector over Wi-Fi
#
# Toolchain location can be overridden with TOOLS=... (default C:\android-build).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TB="${TOOLS:-/c/android-build}"
ADB="${ADB:-/c/platform-tools/adb.exe}"
export JAVA_HOME="$TB/jdk" PATH="$TB/jdk/bin:$TB/build-tools:$PATH"
JAR="$TB/platform/android.jar"

if [ "${1:-}" = helper ]; then
  shift
  cd "$ROOT/projector-helper"; OUT=projector-helper.apk; MIN=21; TARGET=25
  ADB_DEV="-s ${PROJECTOR:-}"; USER_ARG=""
else
  cd "$ROOT/phone-remote"; OUT=projector-remote.apk; MIN=31; TARGET=35
  ADB_DEV="-d"; USER_ARG="--user 0" # main profile only, not Samsung Dual Apps / Secure Folder
  java src/com/projector/remote/Keys.java   # self-check of the key mapping
fi

rm -rf build && mkdir -p build/gen build/classes
aapt2.exe compile --dir res -o build/res.zip
aapt2.exe link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version $MIN --target-sdk-version $TARGET --version-code 1 --version-name 1.0 \
  -o build/unsigned.apk build/res.zip
javac -nowarn -Xlint:-options --release 17 -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java') 2>&1 | grep -v "^Note:" || true
[ -n "$(find build/classes -name '*Activity.class')" ] || { echo "javac failed"; exit 1; }
cmd //c d8.bat --lib "$(cygpath -w "$JAR")" --min-api $MIN --output build $(find build/classes -name '*.class' | xargs -n1 cygpath -w)
(cd build && jar --update --no-manifest --file unsigned.apk classes.dex)
zipalign.exe -p -f 4 build/unsigned.apk build/aligned.apk

# debug.jks is committed on purpose so every clone signs with the same key and can update installed apps
KS="$ROOT/debug.jks"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Projector Remote" >/dev/null
cmd //c apksigner.bat sign --ks "$(cygpath -w "$KS")" --ks-pass pass:android --out $OUT build/aligned.apk
echo "Built $(pwd)/$OUT"

if [ "${1:-}" = install ]; then
  if [ "$ADB_DEV" = "-s " ]; then echo "Set PROJECTOR=<projector-ip>:5555 to install the helper"; exit 1; fi
  "$ADB" $ADB_DEV install -r $USER_ARG $OUT
fi
