param(
    [string]$RapfiDir = "$env:TEMP\rapfi-src"
)

# Patches Rapfi's CMakeLists.txt so it does not link -lpthread on Android.
# On Android, pthread is part of bionic libc, so there is no separate libpthread
# and the default Rapfi link line ("pthread atomic") fails with:
#   ld.lld: error: unable to find library -lpthread

$file = Join-Path $RapfiDir 'Rapfi\CMakeLists.txt'
if (-not (Test-Path -LiteralPath $file)) {
    Write-Host "[patch] Not found: $file" -ForegroundColor Red
    exit 1
}

$text = Get-Content -Raw -LiteralPath $file

if ($text.Contains('CMAKE_SYSTEM_NAME STREQUAL "Android"')) {
    Write-Host "[patch] Already applied."
    exit 0
}

$needle = '    if(WIN32)'
if (-not $text.Contains($needle)) {
    Write-Host "[patch] Anchor not found: $needle" -ForegroundColor Red
    exit 1
}

$replacement = @'
    if(CMAKE_SYSTEM_NAME STREQUAL "Android")
        # Android bionic libc already provides pthread; do not link -lpthread.
        target_link_libraries(rapfi PRIVATE atomic)
    elseif(WIN32)
'@

$text = $text.Replace($needle, $replacement)
Set-Content -NoNewline -LiteralPath $file -Value $text
Write-Host "[patch] Applied Android link fix to $file"
