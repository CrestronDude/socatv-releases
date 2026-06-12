@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   SocaTV Nova - Git Sync
echo ============================================
echo.

cd /d "C:\SocaTvNova"

:: Pull latest first
echo [1/3] Pulling latest from GitHub...
git pull origin main
if errorlevel 1 (
    echo ERROR: git pull failed. Check connection or resolve conflicts.
    pause
    exit /b 1
)

:: Stage all changes
echo.
echo [2/3] Staging changes...
git add -A

:: Check if there's anything to commit
git diff --cached --quiet
if errorlevel 1 (
    :: Get commit message from argument or prompt
    set MSG=%~1
    if "!MSG!"=="" (
        set /p MSG=Commit message:
    )
    if "!MSG!"=="" set MSG=Update source

    echo.
    echo [3/3] Committing and pushing...
    git commit -m "!MSG!"
    git push origin main
    if errorlevel 1 (
        echo ERROR: git push failed.
        pause
        exit /b 1
    )
    echo.
    echo Sync complete. Changes pushed to GitHub.
) else (
    echo.
    echo Nothing to commit. Already up to date.
)

echo.
pause
