#!/usr/bin/env bash
# record_hearing.sh — Capture system audio (monitor source) to WAV
# Format: 16kHz mono 16-bit (whisper-friendly)
# Usage: bash ~/Documents/UTS/scripts/record_hearing.sh

RECORDINGS_DIR="$HOME/Documents/UTS/recordings"
DEVICE="alsa_output.pci-0000_c6_00.6.analog-stereo.monitor"
TIMESTAMP=$(date +%Y%m%d_%H%M)
OUTPUT="$RECORDINGS_DIR/smac_hearing_${TIMESTAMP}.wav"

mkdir -p "$RECORDINGS_DIR"

cleanup() {
    echo ""
    echo "=========================================="
    echo " RECORDING STOPPED"
    echo " Saved to: $OUTPUT"
    ls -lh "$OUTPUT" 2>/dev/null
    echo "=========================================="
    echo ""
    echo "To transcribe, run:"
    echo "  bash ~/Documents/UTS/scripts/transcribe_hearing.sh \"$OUTPUT\""
    exit 0
}

trap cleanup INT TERM

echo ""
echo "=========================================="
echo " RECORDING STARTED"
echo " Source: $DEVICE"
echo " Output: $OUTPUT"
echo " Format: 16kHz mono 16-bit WAV"
echo " Press Ctrl+C to stop recording"
echo "=========================================="
echo ""

parecord \
    --device="$DEVICE" \
    --rate=16000 \
    --channels=1 \
    --format=s16le \
    "$OUTPUT"

cleanup
