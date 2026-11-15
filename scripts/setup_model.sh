#!/usr/bin/env bash
#
# scripts/setup_model.sh — Download and install Vosk English acoustic model to device
#
set -euo pipefail

MODEL_NAME="vosk-model-small-en-us-0.15"
MODEL_URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"
DEST_DEVICE_DIR="/sdcard/Android/data/dev.nuc.okgoogle/files/model"
CACHE_DIR="${TMPDIR:-/tmp}/vosk_cache"

echo "=== Vosk Model Setup ==="
echo "Model: ${MODEL_NAME}"
echo "Target on device: ${DEST_DEVICE_DIR}"

mkdir -p "${CACHE_DIR}"

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

echo "Checking ADB device connection..."
adb get-state >/dev/null 2>&1 || {
    echo "Error: No ADB device connected. Ensure device is reachable via adb devices."
    exit 1
}

echo "Stopping WakeService if running..."
adb shell am broadcast -a dev.nuc.okgoogle.STOP >/dev/null 2>&1 || true

echo "Preparing target directory on device..."
adb shell "mkdir -p ${DEST_DEVICE_DIR}"
adb shell "rm -rf ${DEST_DEVICE_DIR}/*"

echo "Pushing model files to device..."
adb push "${CACHE_DIR}/${MODEL_NAME}/"* "${DEST_DEVICE_DIR}/"

echo "Verifying installation on device..."
adb shell "ls -la ${DEST_DEVICE_DIR}"

echo "Starting WakeService..."
adb shell am broadcast -a dev.nuc.okgoogle.START >/dev/null 2>&1 || true

echo "Model setup complete! Vosk English model is active."
