# Debugging HNS Go in Android Studio

## Quick Steps

### 1. View Logcat
- **View → Tool Windows → Logcat** (or press `Alt+6`)
- Filter by tag: `HNSGo` or level: `Error`
- Look for red error messages with stack traces

### 2. Use Debugger
- Click left margin to set **breakpoints** (red dots)
- **Run → Debug 'app'** (or `Shift+F9`)
- App will pause at breakpoints
- Use **Variables** panel to inspect values
- Use **F8** to step over, **F7** to step into

### 3. Filter Logcat Commands
In Logcat filter box, use:
- `tag:HNSGo` - See only our app logs
- `level:error` - See only errors
- `tag:HNSGo level:error` - Combined filter

### 4. Common Crash Points
Check these areas:
- `MainActivity.kt` line 60-70: SPV sync initialization
- `DohService.kt` line 21-43: Service creation
- `DohService.kt` line 30-35: DoH server startup
- Network permissions in AndroidManifest.xml

### 5. Check Stack Trace
Look for lines like:
```
FATAL EXCEPTION: main
Process: com.acktarius.hnsgo
...
at com.acktarius.hnsgo.MainActivity$HnsGoScreen$1$1.invoke(MainActivity.kt:XX)
```
The line number (XX) shows where it crashed.

### 6. ADB Commands (Optional)
If you have ADB installed:
```bash
# View all logs
adb logcat

# Filter by tag
adb logcat -s HNSGo

# Clear logs
adb logcat -c

# View crash logs only
adb logcat *:E
```

## What to Look For

1. **Network errors**: Check if device has internet
2. **Port conflicts**: Port 8443 might be in use
3. **Permission issues**: Check AndroidManifest.xml
4. **Null pointer exceptions**: Check if variables are initialized
5. **Coroutine errors**: Check if scope is properly handled

