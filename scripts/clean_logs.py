#!/usr/bin/env python3
"""
Script to clean up logging statements for privacy:
1. Remove all Log.d (debug) lines
2. Remove any log lines containing domain names or IP addresses
"""

import re
import os
import sys
from pathlib import Path

# Patterns to identify domain names and IP addresses
# Domain: at least one dot, valid TLD-like structure
DOMAIN_PATTERN = r'\b[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+\.?'
# IPv4: 4 groups of 1-3 digits
IP_PATTERN = r'\b(?:\d{1,3}\.){3}\d{1,3}\b'
# IPv6: simplified pattern
IPV6_PATTERN = r'\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\b'

# Pattern to match any Log statement (d, i, w, e) - handles both Log.d and android.util.Log.d
LOG_PATTERN = r'^\s*(android\.util\.)?Log\.(d|i|w|e)\([^)]*\);?\s*$'

# Excluded patterns (common false positives)
EXCLUDED_DOMAINS = [
    'android', 'java', 'kotlin', 'com.acktarius', 'package', 'import',
    'org.xbill', 'org.bouncycastle', 'fi.iki.elonen'
]

def contains_sensitive_data(line):
    """Check if a line contains domain names, IP addresses, hashes, or TLDs"""
    # Check for IP addresses first (more reliable)
    if re.search(IP_PATTERN, line) or re.search(IPV6_PATTERN, line):
        # Exclude common DNS server IPs in comments/constants
        if '9.9.9.9' in line or '1.1.1.1' in line or '127.0.0.1' in line:
            # Only exclude if it's clearly a constant/comment, not in a log message
            if '//' in line or 'const' in line or 'val' in line:
                return False
        return True
    
    # Check for domain names
    domain_matches = re.finditer(DOMAIN_PATTERN, line, re.IGNORECASE)
    for match in domain_matches:
        domain = match.group(0).lower().rstrip('.')
        # Exclude common false positives
        if not any(ex in domain for ex in EXCLUDED_DOMAINS):
            # Check if it's a real domain (has at least 2 parts)
            parts = domain.split('.')
            if len(parts) >= 2:
                return True
    
    # Check for hashes (hex strings that look like hashes - 32+ hex chars)
    # Pattern: sequences of hex characters that are 32+ chars (likely SHA256/SHA3 hashes)
    hash_pattern = r'\b[0-9a-fA-F]{32,}\b'
    if re.search(hash_pattern, line):
        return True
    
    # Check for TLD references in log messages
    if 'Log.' in line and ('TLD' in line or 'tld' in line):
        return True
    
    # Check for domain/name references in log messages
    if 'Log.' in line and ('domain' in line.lower() or 'name' in line.lower()):
        # But exclude common words like "domainName", "domainNameStr", etc. in code
        if "'" in line or '"' in line:  # Likely a log message with a string
            return True
    
    return False

def is_log_statement(line):
    """Check if line is any Log statement (handles both Log.d and android.util.Log.d)"""
    return re.match(LOG_PATTERN, line.strip()) is not None or 'android.util.Log.d' in line

def clean_file(file_path):
    """Clean a single Kotlin file"""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except Exception as e:
        print(f"Error reading {file_path}: {e}")
        return False
    
    cleaned_lines = []
    removed_count = 0
    
    for i, line in enumerate(lines, 1):
        original_line = line
        
        # Remove all Log.d statements (including android.util.Log.d)
        if ('Log.d' in line or 'android.util.Log.d' in line) and is_log_statement(line):
            removed_count += 1
            continue
        
        # Remove log statements containing sensitive data (domain names or IPs)
        if 'Log.' in line and contains_sensitive_data(line):
            removed_count += 1
            continue
        
        cleaned_lines.append(line)
    
    if removed_count > 0:
        try:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.writelines(cleaned_lines)
            print(f"✓ {file_path}: Removed {removed_count} log lines")
            return True
        except Exception as e:
            print(f"Error writing {file_path}: {e}")
            return False
    
    return False

def main():
    """Main function"""
    if len(sys.argv) > 1:
        target_dir = Path(sys.argv[1])
    else:
        target_dir = Path(__file__).parent.parent / "android" / "app" / "src" / "main" / "java" / "com" / "acktarius" / "hnsgo"
    
    if not target_dir.exists():
        print(f"Error: Directory {target_dir} does not exist")
        sys.exit(1)
    
    print(f"Cleaning log statements in: {target_dir}")
    print("=" * 60)
    
    kotlin_files = list(target_dir.rglob("*.kt"))
    
    if not kotlin_files:
        print("No Kotlin files found")
        sys.exit(0)
    
    cleaned_count = 0
    for kt_file in kotlin_files:
        if clean_file(kt_file):
            cleaned_count += 1
    
    print("=" * 60)
    print(f"Cleaned {cleaned_count} files")

if __name__ == "__main__":
    main()

