#!/bin/sh
# Builds the launcher APK without Gradle and optionally installs it over ADB.
#
#   sh build.sh            -> build/CleanLauncher.apk
#   sh build.sh install    -> also `adb install -r` (RADIO=<ip>:5555 to pick a device)
#
# Works on Linux, macOS and Windows (Git Bash). Override any of these if needed:
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK location
#   ANDROID_PLATFORM                  compile platform (default android-33)
#   BUILD_TOOLS                       build-tools version (default: newest installed)
#   JAVA_HOME                         JDK 17+ (default: javac on PATH, then Android Studio's JBR)
#   KEYSTORE / KEYSTORE_PASS / KEY_ALIAS   signing key (default: ~/.android/debug.keystore)
set -e
cd "$(dirname "$0")"

MIN_SDK=25        # Android 7.1, the NWD head unit
TARGET_SDK=28
APK_NAME=CleanLauncher.apk

die() { echo "error: $*" >&2; exit 1; }

# ---------- Host OS: Windows tools carry .exe/.bat suffixes ----------
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) WIN=1; EXE=.exe; BAT=.bat ;;
    *)                    WIN=0; EXE=;     BAT=     ;;
esac

# ---------- Android SDK ----------
SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
if [ -z "$SDK" ]; then
    for d in "$LOCALAPPDATA/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [ -d "$d" ] && SDK=$d && break
    done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Android SDK not found. Set ANDROID_HOME (or ANDROID_SDK_ROOT)."
[ "$WIN" = 1 ] && command -v cygpath >/dev/null && SDK=$(cygpath -u "$SDK")

PLATFORM=${ANDROID_PLATFORM:-android-33}
JAR=$SDK/platforms/$PLATFORM/android.jar
[ -f "$JAR" ] || die "missing $PLATFORM. Install it with: sdkmanager \"platforms;$PLATFORM\""

if [ -z "$BUILD_TOOLS" ]; then
    BUILD_TOOLS=$(ls "$SDK/build-tools" 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)
fi
BT=$SDK/build-tools/$BUILD_TOOLS
[ -n "$BUILD_TOOLS" ] && [ -x "$BT/aapt2$EXE" ] \
    || die "Android build-tools not found. Install them with: sdkmanager \"build-tools;36.0.0\""

# ---------- JDK ----------
if [ -n "$JAVA_HOME" ]; then
    JDK=$JAVA_HOME/bin
elif command -v javac >/dev/null 2>&1; then
    JDK=$(dirname "$(command -v javac)")
else
    for d in "/c/Program Files/Android/Android Studio/jbr" "/c/Program Files/Android/Android Studio1/jbr" \
             "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "/opt/android-studio/jbr"; do
        [ -x "$d/bin/javac$EXE" ] && JDK=$d/bin && break
    done
fi
[ -n "$JDK" ] && [ -x "$JDK/javac$EXE" ] || die "JDK 17+ not found. Install one or set JAVA_HOME."
[ "$WIN" = 1 ] && command -v cygpath >/dev/null && JDK=$(cygpath -u "$JDK")
export JAVA_HOME="${JDK%/bin}"

JAVA_MAJOR=$("$JDK/javac$EXE" -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')
[ "$JAVA_MAJOR" -ge 17 ] 2>/dev/null || die "JDK 17+ required (found: $("$JDK/javac$EXE" -version 2>&1))."

# ---------- Signing key: debug keystore, created if missing ----------
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KEYSTORE_PASS=${KEYSTORE_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-androiddebugkey}
if [ ! -f "$KEYSTORE" ]; then
    echo "Creating debug keystore at $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$JDK/keytool$EXE" -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" \
        -keypass "$KEYSTORE_PASS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

echo "SDK $SDK | $PLATFORM | build-tools $BUILD_TOOLS | JDK $JAVA_MAJOR"

# ---------- Build ----------
rm -rf build && mkdir -p build/gen build/classes build/dex

# VARIANT=share: build to give away. Leaves out the owner's recorded voice
# clips (the app then uses the user's own photo and the device's text-to-speech).
RES=res
if [ "$VARIANT" = share ]; then
    RES=build/res-share
    cp -r res "$RES"
    rm -f "$RES"/raw/v_*.mp3
    rmdir "$RES"/raw "$RES"/drawable-nodpi 2>/dev/null || true
    APK_NAME=CleanLauncher-compartir.apk
fi

"$BT/aapt2$EXE" compile --dir "$RES" -o build/res.zip
"$BT/aapt2$EXE" link -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --java build/gen -o build/unsigned.apk build/res.zip

"$JDK/javac$EXE" -nowarn -Xlint:-options --release 8 -encoding UTF-8 \
    -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

"$BT/d8$BAT" --min-api $MIN_SDK --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

(cd build/dex && "$JDK/jar$EXE" uf ../unsigned.apk classes.dex)
"$BT/zipalign$EXE" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner$BAT" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --ks-key-alias "$KEY_ALIAS" \
    --out "build/$APK_NAME" build/aligned.apk

echo "APK: build/$APK_NAME"

if [ "$1" = "install" ]; then
    ADB=$SDK/platform-tools/adb$EXE
    [ -x "$ADB" ] || die "adb not found. Install it with: sdkmanager \"platform-tools\""
    if [ -n "$RADIO" ]; then
        "$ADB" connect "$RADIO" >/dev/null 2>&1 || true
        "$ADB" -s "$RADIO" install -r "build/$APK_NAME"
    else
        "$ADB" install -r "build/$APK_NAME"
    fi
fi
