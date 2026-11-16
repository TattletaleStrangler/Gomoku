# Diagnoses the Rapfi engine on a connected Android device over adb.
#
# The engine is run DIRECTLY from nativeLibraryDir (the only place Android 10+
# allows exec) with filesDir/engine as the working directory, and is fed
# Gomocup/Piskvork commands on stdin.
#
# Tests:
#   A) empty board: START + INFO timeout_turn + BEGIN
#   B) BOARD sent in MOVE ORDER (what the app does now)
#   C) BOARD sent in COORDINATE order (what the app used to do)
#
# Rapfi derives its own color from the FIRST stone of a BOARD command
# (see Rapfi/command/gomocup.cpp, getPosition), so C is expected to fail while
# B succeeds.
#
# Usage:
#   pwsh -File tools\test_rapfi_adb.ps1
#   pwsh -File tools\test_rapfi_adb.ps1 -Package com.oldmermaidman.gomoku

param(
    [string]$Package = "com.oldmermaidman.gomoku",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Continue"

if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Host "adb not found at: $Adb" -ForegroundColor Red
    Write-Host "Pass the path explicitly, e.g.:"
    Write-Host "  pwsh -File tools\test_rapfi_adb.ps1 -Adb 'C:\path\to\adb.exe'"
    exit 1
}

# ---- device ---------------------------------------------------------------

$devices = & $Adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if (-not $devices) {
    Write-Host "No device connected." -ForegroundColor Yellow
    Write-Host "1. On the phone: Settings -> About -> tap Build number 7x (Developer options)."
    Write-Host "2. Enable 'USB debugging' in Developer options."
    Write-Host "3. Connect the USB cable and accept the 'Allow USB debugging' prompt."
    Write-Host "4. Re-run this script."
    exit 1
}
Write-Host "Device: $($devices -join ', ')" -ForegroundColor Green

# ---- locate the engine binary (nativeLibraryDir) --------------------------

$pmPath = (& $Adb shell pm path $Package 2>$null | Out-String).Trim()
if ($pmPath -notmatch 'package:') {
    Write-Host "Package $Package is not installed: $pmPath" -ForegroundColor Red
    exit 1
}
$apkLine = ($pmPath -split "`r?`n" | Where-Object { $_ -match 'base\.apk' } | Select-Object -First 1).Trim()
$apk = $apkLine -replace '^package:', ''
$base = $apk -replace '/base\.apk$', ''
$lib = "$base/lib/arm64/librapfi.so"

Write-Host "APK: $apk"
Write-Host "Lib: $lib"
Write-Host ""

# ---- stage a test script on the device ------------------------------------
# A file avoids all host->device shell quoting problems: the script is piped
# into `cat` over adb stdin, then executed with `sh`.

$testScript = @'
PKG_PLACEHOLDER
LIB_PLACEHOLDER

cd files/engine 2>/dev/null || { echo "files/engine not found - is the app installed and started once?"; exit 1; }

echo "=========== ABI / files ==========="
getprop ro.product.cpu.abi
echo "--- data dir: $(pwd) ---"
ls -la
echo "--- binary ---"
ls -la "$LIB"
echo

run() {
    echo "=========== $1 ==========="
    printf "$2" | "$LIB" &
    eng=$!
    ( sleep 15; kill -9 $eng 2>/dev/null ) &
    wd=$!
    wait $eng
    rc=$?
    kill -9 $wd 2>/dev/null
    wait $wd 2>/dev/null
    echo "[engine exited, rc=$rc]"
    echo
}

# A: empty board, engine plays first
run "A: START + INFO timeout_turn + BEGIN (empty board)" \
    'START 15\nINFO rule 0\nINFO game_type 0\nINFO timeout_turn 3000\nBEGIN\nEND\n'

# B: same position, stones in MOVE ORDER (black 7,7 first, then white 6,6)
run "B: BOARD in MOVE order (expected: a move)" \
    'START 15\nINFO rule 0\nINFO game_type 0\nINFO timeout_turn 3000\nBOARD\n7,7,1\n6,6,2\nDONE\nEND\n'

# C: same position, stones in COORDINATE order (white 6,6 first)
run "C: BOARD in COORDINATE order (expected: no move / error)" \
    'START 15\nINFO rule 0\nINFO game_type 0\nINFO timeout_turn 3000\nBOARD\n6,6,2\n7,7,1\nDONE\nEND\n'

echo "=========== done ==========="
'@

# Literal replacement (not -replace, where '$' would be regex-special).
$testScript = $testScript.Replace('PKG_PLACEHOLDER', "PKG=$Package")
$testScript = $testScript.Replace('LIB_PLACEHOLDER', "LIB=$lib")

$stageCmd = "run-as $Package sh -c 'cat > files/rapfi_test.sh'"
Write-Host "Staging test script on device..." -ForegroundColor Cyan
$testScript | & $Adb shell $stageCmd 2>&1 | ForEach-Object { Write-Host "  $_" }

Write-Host ""
Write-Host "Running tests on device (up to ~50 s)..." -ForegroundColor Cyan
Write-Host ""

& $Adb shell "run-as $Package sh files/rapfi_test.sh" 2>&1 | ForEach-Object { Write-Host $_ }

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "How to read this" -ForegroundColor Cyan
Write-Host "  A prints a move (e.g. 7,7)          -> engine + weights are fine"
Write-Host "  B prints a move                     -> the app's current order is correct"
Write-Host "  C prints an ERROR / no move         -> confirms the coordinate-order bug"
Write-Host ""
Write-Host "Also useful: logcat from the running app"
Write-Host "  $Adb logcat -s RapfiGomokuEngine NativeExe"
