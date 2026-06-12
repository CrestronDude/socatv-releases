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
set KEYSTORE=C:\apktools\socatv.keystore
set KS_ALIAS=socatv
set KS_PASS=socatv123
set BT=C:\Android\Sdk\build-tools\36.0.0
set APK_OUT=C:\apktools\SocaTvNova.apk

cd /d "%PROJECT_DIR%"

if not exist gradlew.bat (
    echo ERROR: gradlew.bat not found.
    pause & exit /b 1
)

:: Parse argument
set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=debug

:: Pull latest source before building
echo [0/5] Pulling latest source from GitHub...
git pull origin main
echo.

echo [1/5] Cleaning previous build...
call gradlew.bat clean
if errorlevel 1 ( echo ERROR: Clean failed & pause & exit /b 1 )

echo.
echo [2/5] Building %BUILD_TYPE% APK...
if "%BUILD_TYPE%"=="release" (
    call gradlew.bat assembleRelease
) else (
    call gradlew.bat assembleDebug
)
if errorlevel 1 ( echo ERROR: Build failed & pause & exit /b 1 )

echo.
echo [3/5] Locating and signing APK...

if "%BUILD_TYPE%"=="release" (
    set UNSIGNED=%PROJECT_DIR%\app\build\outputs\apk\release\app-release-unsigned.apk
    if not exist "!UNSIGNED!" set UNSIGNED=%PROJECT_DIR%\app\build\outputs\apk\release\app-release.apk

    set ALIGNED=C:\apktools\SocaTvNova_aligned.apk

    "%BT%\zipalign.exe" -f -v 4 "!UNSIGNED!" "!ALIGNED!" >nul 2>&1
    if errorlevel 1 ( echo ERROR: zipalign failed & pause & exit /b 1 )

    "%BT%\apksigner.bat" sign --ks "%KEYSTORE%" --ks-key-alias %KS_ALIAS% --ks-pass pass:%KS_PASS% --key-pass pass:%KS_PASS% --out "%APK_OUT%" "!ALIGNED!"
    if errorlevel 1 ( echo ERROR: apksigner failed & pause & exit /b 1 )

    del "!ALIGNED!" >nul 2>&1
    echo Signed APK: %APK_OUT%
) else (
    set APK_DEBUG=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk
    if not exist "!APK_DEBUG!" ( echo ERROR: Debug APK not found & pause & exit /b 1 )
    copy "!APK_DEBUG!" "%APK_OUT%" >nul
    echo Debug APK copied to: %APK_OUT%
)

echo.
echo [4/5] Installing to device %DEVICE_IP%:%DEVICE_PORT%...
"%ADB%" connect %DEVICE_IP%:%DEVICE_PORT% >nul 2>&1
timeout /t 2 /nobreak >nul

"%ADB%" -s %DEVICE_IP%:%DEVICE_PORT% install -r "%APK_OUT%"
if errorlevel 1 (
    echo WARNING: Device not connected — APK saved to %APK_OUT%
) else (
    echo Install SUCCESS
    "%ADB%" -s %DEVICE_IP%:%DEVICE_PORT% shell am start -n %PACKAGE%/.ui.splash.SplashActivity
)

:: Auto-push source to git after a release build
if "%BUILD_TYPE%"=="release" (
    echo.
    echo [5/5] Pushing source to GitHub...
    git add -A
    git diff --cached --quiet
    if errorlevel 1 (
        for /f "tokens=*" %%v in ('powershell -Command "(Select-String -Path app\build.gradle -Pattern ""versionName\s+""""(.+)"""" -AllMatches).Matches.Groups[1].Value"') do set VER=%%v
        git commit -m "Build !VER! release"
        git push origin main
        echo Source pushed to GitHub.
    ) else (
        echo No source changes to push.
    )
) else (
    echo [5/5] Skipping git push for debug build.
)

echo.
echo ============================================
echo  Build complete. APK: %APK_OUT%
echo ============================================
pause
