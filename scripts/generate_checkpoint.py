#!/usr/bin/env python3
"""
Generate checkpoint.dat from hnsd's checkpoints.h

This script extracts the HSK_CHECKPOINT_MAIN array from checkpoints.h
and converts the hex string literals to binary format.

Usage:
    python scripts/generate_checkpoint.py [checkpoints.h] [output.dat]

If arguments are not provided, defaults to:
    android/checkpoints.h -> android/app/src/main/assets/checkpoint.dat
"""

import re
import sys
import os
from pathlib import Path


def extract_hex_strings(content):
    """
    Extract all hex string literals from the checkpoint array.
    Handles strings like "\x00\x02\x13\x40" and concatenates them.
    """
    # Pattern to match hex escape sequences: \x followed by 2 hex digits
    hex_pattern = r'\\x([0-9a-fA-F]{2})'
    
    # Find all hex escape sequences in the content
    matches = re.findall(hex_pattern, content)
    
    # Convert hex strings to bytes
    bytes_data = bytes([int(hex_str, 16) for hex_str in matches])
    
    return bytes_data


def extract_checkpoint_array(file_path):
    """
    Extract the HSK_CHECKPOINT_MAIN array content from checkpoints.h
    """
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Find the array definition
    # Pattern: static const uint8_t HSK_CHECKPOINT_MAIN[...] = { ... };
    array_start = content.find('HSK_CHECKPOINT_MAIN[')
    if array_start == -1:
        raise ValueError("Could not find HSK_CHECKPOINT_MAIN array in file")
    
    # Find the opening brace after the array declaration
    brace_start = content.find('{', array_start)
    if brace_start == -1:
        raise ValueError("Could not find opening brace for HSK_CHECKPOINT_MAIN array")
    
    # Find the closing brace (matching the opening one)
    brace_count = 0
    brace_end = brace_start
    
    for i in range(brace_start, len(content)):
        if content[i] == '{':
            brace_count += 1
        elif content[i] == '}':
            brace_count -= 1
            if brace_count == 0:
                brace_end = i
                break
    
    if brace_count != 0:
        raise ValueError("Could not find matching closing brace for HSK_CHECKPOINT_MAIN array")
    
    # Extract the array content (between braces)
    array_content = content[brace_start + 1:brace_end]
    
    return array_content


def main():
    # Default paths
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    
    if len(sys.argv) >= 2:
        input_file = Path(sys.argv[1])
    else:
        input_file = project_root / "android" / "checkpoints.h"
    
    if len(sys.argv) >= 3:
        output_file = Path(sys.argv[2])
    else:
        output_file = project_root / "android" / "app" / "src" / "main" / "assets" / "checkpoint.dat"
    
    # Validate input file exists
    if not input_file.exists():
        print(f"Error: Input file not found: {input_file}", file=sys.stderr)
        sys.exit(1)
    
    print(f"Reading checkpoint data from: {input_file}")
    
    try:
        # Extract array content
        array_content = extract_checkpoint_array(input_file)
        
        # Convert hex strings to bytes
        checkpoint_data = extract_hex_strings(array_content)
        
        # Expected size: 4 (height) + 32 (chainwork) + 150 * 236 (headers) = 35436 bytes
        expected_size = 4 + 32 + (150 * 236)
        
        print(f"Extracted {len(checkpoint_data)} bytes of checkpoint data")
        print(f"Expected size: {expected_size} bytes")
        
        if len(checkpoint_data) != expected_size:
            print(f"Warning: Size mismatch! Expected {expected_size} bytes, got {len(checkpoint_data)}", file=sys.stderr)
            print("This may indicate an issue with the checkpoint extraction.", file=sys.stderr)
        else:
            print("Size matches expected format ✓")
        
        # Verify first 4 bytes are height 136000 (0x00021340 in big-endian)
        if len(checkpoint_data) >= 4:
            height_bytes = checkpoint_data[0:4]
            height = (height_bytes[0] << 24) | (height_bytes[1] << 16) | (height_bytes[2] << 8) | height_bytes[3]
            print(f"Checkpoint height: {height} (expected: 136000)")
            
            if height != 136000:
                print(f"Warning: Height mismatch! Expected 136000, got {height}", file=sys.stderr)
            else:
                print("Height verification passed ✓")
        
        # Create output directory if it doesn't exist
        output_file.parent.mkdir(parents=True, exist_ok=True)
        
        # Write binary file
        with open(output_file, 'wb') as f:
            f.write(checkpoint_data)
        
        print(f"Successfully wrote checkpoint to: {output_file}")
        print(f"File size: {len(checkpoint_data)} bytes")
        
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

