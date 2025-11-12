#!/bin/bash
# Helper script to serve Handshake checkpoint via HTTP
# Run this on your local machine (192.168.1.8) where hnsd is installed
# The Android app will fetch checkpoint from http://192.168.1.8:8080/checkpoint.dat

PORT=8080
CHECKPOINT_FILE="checkpoint.dat"

echo "=== Handshake Checkpoint HTTP Server ==="
echo ""

# Find hnsd checkpoint file
CHECKPOINT_PATHS=(
    "/var/lib/hnsd/checkpoint.dat"
    "$HOME/.hnsd/checkpoint.dat"
    "/usr/local/var/hnsd/checkpoint.dat"
    "./checkpoint.dat"
    "./hnsd/checkpoint.dat"
)

CHECKPOINT_PATH=""
for path in "${CHECKPOINT_PATHS[@]}"; do
    if [ -f "$path" ]; then
        CHECKPOINT_PATH="$path"
        echo "Found checkpoint file: $CHECKPOINT_PATH"
        break
    fi
done

if [ -z "$CHECKPOINT_PATH" ]; then
    echo "ERROR: Checkpoint file not found!"
    echo ""
    echo "To create checkpoint file from hnsd:"
    echo "1. Find hnsd data directory (usually ~/.hnsd or /var/lib/hnsd)"
    echo "2. Look for checkpoint.dat file"
    echo "3. Or extract from hnsd source: checkpoints.h"
    echo ""
    echo "Alternatively, you can:"
    echo "1. Copy checkpoint.dat to current directory"
    echo "2. Run this script again"
    exit 1
fi

# Copy checkpoint to current directory if needed
if [ "$CHECKPOINT_PATH" != "./checkpoint.dat" ]; then
    echo "Copying checkpoint to current directory..."
    cp "$CHECKPOINT_PATH" ./checkpoint.dat
    CHECKPOINT_PATH="./checkpoint.dat"
fi

FILE_SIZE=$(stat -f%z "$CHECKPOINT_PATH" 2>/dev/null || stat -c%s "$CHECKPOINT_PATH" 2>/dev/null)
echo "Checkpoint file size: $FILE_SIZE bytes"
echo ""
echo "Starting HTTP server on port $PORT..."
echo "Serving: http://$(hostname -I | awk '{print $1}'):$PORT/checkpoint.dat"
echo "Or: http://192.168.1.8:$PORT/checkpoint.dat"
echo ""
echo "Press Ctrl+C to stop the server"
echo ""

# Start Python HTTP server
python3 -m http.server $PORT 2>/dev/null || \
python -m SimpleHTTPServer $PORT 2>/dev/null || \
{
    echo "ERROR: Python not found. Install Python 3 to use this script."
    echo ""
    echo "Alternative: Use any HTTP server to serve checkpoint.dat"
    exit 1
}

