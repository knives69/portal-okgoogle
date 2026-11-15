#!/usr/bin/env bash
#
# scripts/setup_model.sh — Download and install Vosk acoustic model to device
# Usage:
#   ./scripts/setup_model.sh [en|es]
#
set -euo pipefail

LANG_CHOICE="${1:-en}"
DEST_DEVICE_DIR="/sdcard/Android/data/dev.nuc.okgoogle/files/model"
CACHE_DIR="${TMPDIR:-/tmp}/vosk_cache"

mkdir -p "${CACHE_DIR}"

if [ "${LANG_CHOICE}" = "es" ]; then
    MODEL_NAME="vosk-model-small-es-0.42"
    MODEL_URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"
    LOCAL_FALLBACK="${HOME}/portal-work/model/vosk-model-small-es-0.22"
else
    MODEL_NAME="vosk-model-small-en-us-0.15"
    MODEL_URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"
    LOCAL_FALLBACK=""
fi

echo "=== Vosk Model Setup (${LANG_CHOICE}) ==="
echo "Model: ${MODEL_NAME}"
echo "Target on device: ${DEST_DEVICE_DIR}"

SOURCE_DIR=""
if [ -n "${LOCAL_FALLBACK}" ] && [ -d "${LOCAL_FALLBACK}" ]; then
    echo "Using existing local model from ${LOCAL_FALLBACK}"
    SOURCE_DIR="${LOCAL_FALLBACK}"
else
    if [ ! -f "${CACHE_DIR}/${MODEL_NAME}.zip" ]; then
        echo "Downloading ${MODEL_NAME}.zip (~40MB)..."
        curl -L -o "${CACHE_DIR}/${MODEL_NAME}.zip" "${MODEL_URL}"
    else
        echo "Using cached archive: ${CACHE_DIR}/${MODEL_NAME}.zip"
    fi

    if [ ! -d "${CACHE_DIR}/${MODEL_NAME}" ]; then
        echo "Extracting model archive..."
        unzip -q -o "${CACHE_DIR}/${MODEL_NAME}.zip" -d "${CACHE_DIR}"
    fi
    SOURCE_DIR="${CACHE_DIR}/${MODEL_NAME}"
fi

echo "Checking ADB device connection..."
adb get-state >/dev/null 2>&1 || {
    echo "Error: No ADB device connected. Ensure device is reachable via adb devices."
    exit 1
}

echo "Stopping WakeService if running..."
adb shell am stopservice -n dev.nuc.okgoogle/.WakeService >/dev/null 2>&1 || true

echo "Preparing target directory on device..."
adb shell "mkdir -p ${DEST_DEVICE_DIR}"
adb shell "rm -rf ${DEST_DEVICE_DIR}/*"

echo "Pushing model files to device..."
adb push "${SOURCE_DIR}/"* "${DEST_DEVICE_DIR}/"

echo "Verifying installation on device..."
adb shell "ls -la ${DEST_DEVICE_DIR}"

echo "Starting WakeService..."
adb shell am start-foreground-service -n dev.nuc.okgoogle/.WakeService >/dev/null 2>&1 || true

echo "Model setup complete! Language [${LANG_CHOICE}] is active."
