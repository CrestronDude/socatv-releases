@echo off
setlocal enabledelayedexpansion

echo ============================================
echo    SocaTV Nova - Build Script
echo ============================================
echo.

set PROJECT_DIR=C:\SocaTvNova
set ADB=C:\Android\Sdk\platform-tools\adb.exe
set DEVICE_IP=10.0.0.42
set DEVICE_PORT=5555
set PACKAGE=com.socatv.nova

cd /d "%PROJECT_DIR%"

:: Check Gradle wrapper exists
if not exist gradlew.bat (
    echo ERROR: gradlew.bat not found. Run this from project root.
    pause
    exit /b 1
)

:: Parse argument
set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=debug

echo [1/4] Cleaning previous build...
call gradlew.bat clean
if errorlevel 1 (
    echo ERROR: Clean failed
    pause
    exit /b 1
)

echo.
echo [2/4] Building %BUILD_TYPE% APK...
if "%BUILD_TYPE%"=="release" (
    call gradlew.bat assembleRelease
) else (
    call gradlew.bat assembleDebug
)

if errorlevel 1 (
    echo ERROR: Build failed. Check output above.
    pause
    exit /b 1
)

echo.
echo [3/4] Locating APK...
if "%BUILD_TYPE%"=="release" (
    set APK_PATH=%PROJECT_DIR%\app\build\outputs\apk\release\app-release-unsigned.apk
    if not exist "!APK_PATH!" (
        set APK_PATH=%PROJECT_DIR%\app\build\outputs\apk\release\app-release.apk
    )
) else (
    set APK_PATH=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk
)

if not exist "!APK_PATH!" (
    echo ERROR: APK not found at !APK_PATH!
    pause
    exit /b 1
)

echo APK: !APK_PATH!
echo.

echo [4/4] Installing to device %DEVICE_IP%:%DEVICE_PORT%...
"%ADB%" connect %DEVICE_IP%:%DEVICE_PORT%
timeout /t 2 /nobreak >nul

"%ADB%" -s %DEVICE_IP%:%DEVICE_PORT% install -r "!APK_PATH!"
if errorlevel 1 (
    echo WARNING: Install failed. Is device connected?
    echo Copying APK to C:\apktools\ instead...
    copy "!APK_PATH!" "C:\apktools\SocaTvNova.apk"
    echo APK copied to C:\apktools\SocaTvNova.apk
) else (
    echo.
    echo ============================================
    echo  Install SUCCESS!
    echo  Launching com.socatv.nova...
    echo ============================================
    "%ADB%" -s %DEVICE_IP%:%DEVICE_PORT% shell am start -n %PACKAGE%/.ui.splash.SplashActivity
)

echo.
echo Build complete.
pause
