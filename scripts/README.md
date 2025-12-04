# Checkpoint Generation Script

## Overview

The `generate_checkpoint.py` script extracts the Handshake checkpoint data from `checkpoints.h` and generates a binary `checkpoint.dat` file for use in the Android app.

## What it does

1. Reads `checkpoints.h` from the hnsd source
2. Extracts the `HSK_CHECKPOINT_MAIN` array content
3. Converts hex string literals (`\x00\x02...`) to binary bytes
4. Writes the binary checkpoint file to `android/app/src/main/assets/checkpoint.dat`

## Checkpoint Format

The generated checkpoint file contains:
- **4 bytes**: Start height (136000, big-endian)
- **32 bytes**: Chainwork (big-endian)
- **35400 bytes**: 150 block headers × 236 bytes each (little-endian)
- **Total**: 35436 bytes

This matches the format expected by `hsk_store_inject_checkpoint()` in hnsd.

## Usage

### Basic usage (default paths):
```bash
python scripts/generate_checkpoint.py
```

This will:
- Read from: `android/checkpoints.h`
- Write to: `android/app/src/main/assets/checkpoint.dat`

### Custom paths:
```bash
python scripts/generate_checkpoint.py <input.h> <output.dat>
```

### Example:
```bash
python scripts/generate_checkpoint.py android/checkpoints.h android/app/src/main/assets/checkpoint.dat
```

## Verification

The script automatically verifies:
- File size matches expected format (35436 bytes)
- First 4 bytes represent height 136000

## Requirements

- Python 3.6+
- `checkpoints.h` file (from hnsd source or project's `android/checkpoints.h`)

## Notes

- The checkpoint file must be placed in `android/app/src/main/assets/` for the Android app to access it
- The checkpoint is used to bootstrap the SPV client when P2P sync is unavailable
- The checkpoint contains 150 headers starting from block 136000 (ending at block 136149)

