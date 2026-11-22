#!/usr/bin/env bash
#
# scripts/build.sh — Standalone build script for Portal Ok Google APK
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "=== Building Portal Ok Google ==="

# 1. Locate android.jar (API 28+ required)
if [ -z "${ANDROID_JAR:-}" ]; then
    CANDIDATES=(
        "${ANDROID_HOME:-}/platforms/android-29/android.jar"
        "${ANDROID_HOME:-}/platforms/android-30/android.jar"
        "${ANDROID_HOME:-}/platforms/android-31/android.jar"
        "${ANDROID_HOME:-}/platforms/android-28/android.jar"
        "${HOME}/android-sdk/platforms/android-29/android.jar"
        "${HOME}/android-sdk/platforms/android-28/android.jar"
        "/usr/lib/android-sdk/platforms/android-29/android.jar"
        "/usr/lib/android-sdk/platforms/android-28/android.jar"
        "${HOME}/portal-work/tools/android-29.jar"
    )
    for c in "${CANDIDATES[@]}"; do
        if [ -n "$c" ] && [ -f "$c" ]; then
            ANDROID_JAR="$c"
            break
        fi
    done
fi

if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "${ANDROID_JAR}" ]; then
    echo "Error: android.jar not found."
    echo "Please set ANDROID_JAR=/path/to/platforms/android-29/android.jar or install Android SDK."
    exit 1
fi
echo "Using Android Platform: ${ANDROID_JAR}"

# 2. Locate build tools (AAPT2, D8, ZIPALIGN, APKSIGNER)
AAPT2="$(which aapt2 2>/dev/null || find "${ANDROID_HOME:-/opt/android-sdk}" -name aapt2 2>/dev/null | head -n 1 || true)"
ZIPALIGN="$(which zipalign 2>/dev/null || find "${ANDROID_HOME:-/opt/android-sdk}" -name zipalign 2>/dev/null | head -n 1 || true)"
APKSIGNER="$(which apksigner 2>/dev/null || find "${ANDROID_HOME:-/opt/android-sdk}" -name apksigner 2>/dev/null | head -n 1 || true)"

if [ -z "${AAPT2}" ] && [ -f "${HOME}/deep_carve/bt/aapt2" ]; then
    AAPT2="${HOME}/deep_carve/bt/aapt2"
fi
if [ -z "${ZIPALIGN}" ] && [ -f "${HOME}/deep_carve/bt/zipalign" ]; then
    ZIPALIGN="${HOME}/deep_carve/bt/zipalign"
fi
if [ -z "${APKSIGNER}" ] && [ -f "${HOME}/deep_carve/bt/apksigner" ]; then
    APKSIGNER="${HOME}/deep_carve/bt/apksigner"
fi

if [ -z "${D8:-}" ]; then
    if which d8 >/dev/null 2>&1; then
        D8="$(which d8)"
    elif [ -f "${HOME}/deep_carve/bt/d8" ]; then
        D8="${HOME}/deep_carve/bt/d8"
    elif [ -n "${BUILD_TOOLS:-}" ] && [ -f "${BUILD_TOOLS}/d8" ]; then
        D8="${BUILD_TOOLS}/d8"
    else
        D8="$(find "${ANDROID_HOME:-/opt/android-sdk}" -name d8 2>/dev/null | head -n 1 || true)"
    fi
fi

if [ -z "${AAPT2}" ] || [ ! -f "${AAPT2}" ]; then
    echo "Error: aapt2 not found. Install android-sdk or set in PATH."
    exit 1
fi
if [ -z "${D8}" ] || [ ! -f "${D8}" ]; then
    echo "Error: d8 not found. Install android-sdk or set D8=/path/to/d8."
    exit 1
fi
if [ -z "${ZIPALIGN}" ] || [ ! -f "${ZIPALIGN}" ]; then
    echo "Error: zipalign not found."
    exit 1
fi
if [ -z "${APKSIGNER}" ] || [ ! -f "${APKSIGNER}" ]; then
    echo "Error: apksigner not found."
    exit 1
fi

echo "Using build tools:"
echo "  AAPT2:     ${AAPT2}"
echo "  D8:        ${D8}"
echo "  ZIPALIGN:  ${ZIPALIGN}"
echo "  APKSIGNER: ${APKSIGNER}"

# 3. Ensure debug keystore exists
KEYSTORE="${HOME}/.android/debug.keystore"
if [ ! -f "${KEYSTORE}" ]; then
    echo "Creating debug keystore at ${KEYSTORE}..."
    mkdir -p "$(dirname "${KEYSTORE}")"
    keytool -genkeypair -v \
        -keystore "${KEYSTORE}" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

# 4. Clean & create build scratchpad
rm -rf build gen classes
mkdir -p build gen classes

# 5. Compile resources
echo "Compiling resources..."
"${AAPT2}" compile --dir res -o build/comp.zip

echo "Linking APK package..."
"${AAPT2}" link -o build/base.apk \
    -I "${ANDROID_JAR}" \
    --manifest AndroidManifest.xml \
    --java gen \
    --min-sdk-version 28 \
    --target-sdk-version 28 \
    build/comp.zip

# 6. Compile Java sources
echo "Compiling Java classes..."
CP="${ANDROID_JAR}:libs/vosk-android.jar:libs/jna.jar"
find src gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -nowarn -cp "${CP}" -d classes @build/sources.txt

# 7. Convert classes to DEX
echo "Running D8 DEX conversion..."
"${D8}" --min-api 28 --lib "${ANDROID_JAR}" --output build/ \
    $(find classes -name '*.class') \
    libs/vosk-android.jar \
    libs/jna.jar

# 8. Package APK
echo "Packaging APK..."
cp build/base.apk build/unsigned.apk
(cd build && zip -q -u unsigned.apk classes.dex)
zip -q -r build/unsigned.apk lib/

# 9. Zipalign
echo "Aligning APK..."
"${ZIPALIGN}" -f 4 build/unsigned.apk build/aligned.apk

# 10. Sign APK
echo "Signing APK..."
"${APKSIGNER}" sign \
    --ks "${KEYSTORE}" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out okgoogle.apk \
    build/aligned.apk

echo "SUCCESS! Generated okgoogle.apk"
ls -lh okgoogle.apk
