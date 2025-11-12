#!/bin/bash
# Helper script to check hnsd seed nodes and configuration
# Run this on the machine where hnsd is installed (192.168.1.8)

echo "=== Checking hnsd Seed Nodes ==="
echo ""

# Method 1: Check hnsd source code (seed nodes are in daemon.c or config files)
echo "1. Checking hnsd source for seed nodes:"
for path in "/usr/src/hnsd" "./hnsd" "$HOME/hnsd" "/opt/hnsd"; do
    if [ -f "$path/src/daemon.c" ]; then
        echo "   Found hnsd at: $path"
        echo "   Seed nodes in daemon.c:"
        grep -A 10 -i "seed\|SEED" "$path/src/daemon.c" 2>/dev/null | head -15
        echo ""
        break
    fi
done

# Method 2: Check if hnsd is running and query it
if command -v hnsd &> /dev/null; then
    echo "2. hnsd binary found:"
    which hnsd
    echo ""
    
    # Try to get version/help which might show seed info
    echo "3. hnsd version/help:"
    hnsd --help 2>&1 | head -20
    echo ""
fi

# Method 3: Check hnsd logs if available
if [ -f "/var/log/hnsd.log" ] || [ -f "./hnsd.log" ]; then
    echo "4. Checking hnsd logs for peer connections:"
    tail -50 /var/log/hnsd.log 2>/dev/null | grep -i "seed\|peer\|connect" || \
    tail -50 ./hnsd.log 2>/dev/null | grep -i "seed\|peer\|connect" || \
    echo "   No seed/peer info in logs"
    echo ""
fi

# Method 4: Check running hnsd process
if pgrep -x hnsd > /dev/null; then
    echo "5. hnsd is running (PID: $(pgrep -x hnsd))"
    echo "   Check process with: ps aux | grep hnsd"
    echo ""
fi

# Method 5: Check GitHub for seed nodes
echo "6. hnsd GitHub repository:"
echo "   Seed nodes are defined in: https://github.com/handshake-org/hnsd/blob/master/src/daemon.c"
echo "   Or check: https://raw.githubusercontent.com/handshake-org/hnsd/master/src/daemon.c"
echo ""

# Method 6: Try to fetch from GitHub directly
echo "7. Fetching seed nodes from GitHub (if available):"
curl -s "https://raw.githubusercontent.com/handshake-org/hnsd/master/src/daemon.c" 2>/dev/null | \
    grep -A 5 -i "seed\|SEED" | head -20 || \
    echo "   Could not fetch from GitHub"
echo ""

echo "=== To find seed nodes manually ==="
echo "1. On your local machine (192.168.1.8), run:"
echo "   ssh user@192.168.1.8 'grep -A 10 -i seed /path/to/hnsd/src/daemon.c'"
echo ""
echo "2. Or clone hnsd and check:"
echo "   git clone https://github.com/handshake-org/hnsd"
echo "   grep -A 10 -i seed hnsd/src/daemon.c"
echo ""
echo "3. Check hnsd connections:"
echo "   netstat -an | grep 12038"
echo ""

