@echo off
setlocal

REM Cross-compiles Rapfi for Android arm64-v8a on Windows 10 (CMake + Ninja + NDK).
REM Requirements:
REM   - Android NDK  (set ANDROID_NDK_HOME, or edit the line below)
REM   - CMake        (installed via Android Studio SDK Manager: SDK Tools -> CMake)
REM   - Ninja        (SDK Tools -> Ninja; bundled with Android Studio's CMake)
REM   - git
REM
REM Result:
REM   app/src/main/jniLibs/arm64-v8a/librapfi.so   ← binary (Android 10+ exec)
REM   app/src/main/assets/engine/config.toml       ← config + NNUE weights
REM   app/src/main/assets/engine/classical/
REM   app/src/main/assets/engine/mix9svq/
REM
REM Usage:
REM   set ANDROID_NDK_HOME=%LOCALAPPDATA%\Android\Sdk\ndk\26.3.11579264
REM   tools\build_rapfi.cmd

if "%ANDROID_NDK_HOME%"=="" (
    echo [ERROR] Set ANDROID_NDK_HOME first. Example:
    echo   set ANDROID_NDK_HOME=%%LOCALAPPDATA%%\Android\Sdk\ndk\26.3.11579264
    exit /b 1
)

set "RAPFI_DIR=%TEMP%\rapfi-src"
if not exist "%RAPFI_DIR%" (
    git clone --recursive --depth 1 https://github.com/dotb1ue/rapfi "%RAPFI_DIR%"
    if errorlevel 1 exit /b 1
)
cd /d "%RAPFI_DIR%"

REM Fix: don't link -lpthread on Android (bionic libc already provides pthread).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch_rapfi.ps1" -RapfiDir "%RAPFI_DIR%"
if errorlevel 1 exit /b 1

set "TOOLCHAIN=%ANDROID_NDK_HOME%\build\cmake\android.toolchain.cmake"

cmake -S Rapfi -B build\android-arm64 -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
  -DANDROID_ABI=arm64-v8a ^
  -DANDROID_PLATFORM=android-26 ^
  -DANDROID_STL=c++_static ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DUSE_NEON=ON ^
  -DUSE_NEON_DOTPROD=OFF ^
  -DNO_COMMAND_MODULES=ON
if errorlevel 1 exit /b 1

cmake --build build\android-arm64
if errorlevel 1 exit /b 1

REM ── binary: disguised as a native library so Android 10+ allows exec() ──
set "JNI_DEST=%~dp0..\app\src\main\jniLibs\arm64-v8a"
if not exist "%JNI_DEST%" mkdir "%JNI_DEST%"
copy /y "build\android-arm64\pbrain-rapfi" "%JNI_DEST%\librapfi.so" >nul
echo [OK] Binary copied to %JNI_DEST%\librapfi.so

REM ── data files: config.toml + NNUE weights (copied at runtime by NativeExe) ──
set "ASSETS_DEST=%~dp0..\app\src\main\assets\engine"
if not exist "%ASSETS_DEST%" mkdir "%ASSETS_DEST%"

if exist "%RAPFI_DIR%\Networks\config-example\config.toml" (
    copy /y "%RAPFI_DIR%\Networks\config-example\config.toml" "%ASSETS_DEST%\config.toml" >nul
    REM Fix paths: the Networks-submodule config uses flat filenames, but the
    REM extracted asset tree has classical/ and mix9svq/ subdirectories.
    powershell -NoProfile -Command ^
      "(Get-Content '%ASSETS_DEST%\config.toml' -Raw) -replace 'binary_file = \"model','binary_file = \"classical/model' -replace 'weight_file(_black|_white)? = \"mix9svq','weight_file$1 = \"mix9svq/mix9svq' | Set-Content -NoNewline '%ASSETS_DEST%\config.toml'"
    xcopy /e /i /y "%RAPFI_DIR%\Networks\classical" "%ASSETS_DEST%\classical" >nul
    xcopy /e /i /y "%RAPFI_DIR%\Networks\mix9svq" "%ASSETS_DEST%\mix9svq" >nul
    echo [OK] NNUE weights + config copied to %ASSETS_DEST%
) else (
    echo [WARN] Networks submodule not found; Rapfi will start without NNUE weights.
)

REM Remove any stale pbrain-rapfi left in assets/ from a previous build script.
if exist "%ASSETS_DEST%\pbrain-rapfi" (
    del "%ASSETS_DEST%\pbrain-rapfi"
    echo [OK] Removed stale pbrain-rapfi from assets/engine/
)

echo.
echo Done. Rebuild the APK (Build ^> Build APK) to package the engine.
endlocal