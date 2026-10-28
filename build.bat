@echo off
setlocal enabledelayedexpansion

rem =============================================================
rem  build.bat <versionName> [flavors]
rem
rem  Example:
rem    build.bat 6.2.0
rem    build.bat 6.2.0 origin
rem
rem  What it does:
rem   1. On the very first run: generates a real release keystore
rem      (keystore\release.keystore.jks) with random passwords,
rem      stored in keystore\keystore.properties next to it.
rem   2. On every run after that: reuses the same keystore, so all
rem      builds are signed with the same key.
rem   3. Builds a signed release for every flavor: playstore and
rem      origin. Each flavor produces a "universal" APK plus one
rem      slimmer APK per CPU architecture (armeabi-v7a, arm64-v8a,
rem      x86, x86_64) - 5 APKs per flavor, 10 total by default.
rem   4. Copies every resulting APK into releases\<version>\.
rem
rem  keystore\ is tracked so local builds and GitHub Actions reuse the same key.
rem  Keep a separate backup: losing it prevents signing compatible updates.
rem  Anyone with repository read access can access the signing key and passwords.
rem =============================================================

set VERSION_NAME=%1
if "%VERSION_NAME%"=="" (
    echo Usage: build.bat ^<versionName^> [flavors]
    echo Example: build.bat 6.2.0
    exit /b 1
)

rem optional 2nd arg: comma-separated flavor list, e.g. "origin" or "playstore,origin"
set FLAVORS=%2
if "%FLAVORS%"=="" set FLAVORS=playstore,origin

set SCRIPT_DIR=%~dp0
set KEYSTORE_DIR=%SCRIPT_DIR%keystore
set KEYSTORE_FILE=%KEYSTORE_DIR%\release.keystore.jks
set KEYSTORE_PROPS=%KEYSTORE_DIR%\keystore.properties
set VERSIONCODE_FILE=%KEYSTORE_DIR%\versioncode.txt

rem -------------------------------------------------------------
rem 1. First run: generate a real signing key
rem -------------------------------------------------------------
if not exist "%KEYSTORE_FILE%" (
    echo [build] No release keystore found - generating one now ^(first run^)...
    if not exist "%KEYSTORE_DIR%" mkdir "%KEYSTORE_DIR%"

    rem Random passwords via PowerShell (System.Random is not secure enough
    rem for anything sensitive, but this is fine for a local build key;
    rem swap in your own passwords in keystore.properties any time you like).
    rem PKCS12 (the default keystore format on modern JDKs, used even for
    rem files named *.jks) requires the key password to match the store
    rem password - keytool silently ignores a different -keypass.
    for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command ^
        "-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 24 | ForEach-Object {[char]$_})"`) do set STORE_PASS=%%P
    set KEY_PASS=%STORE_PASS%

    set KEY_ALIAS=leankeyboardf

    keytool -genkeypair -v ^
        -keystore "%KEYSTORE_FILE%" ^
        -alias !KEY_ALIAS! ^
        -keyalg RSA -keysize 2048 -validity 10000 ^
        -storepass "!STORE_PASS!" -keypass "!KEY_PASS!" ^
        -dname "CN=LeanKeyboardF, OU=Release, O=LeanKeyboardF, L=Unknown, S=Unknown, C=UA"

    if errorlevel 1 (
        echo [build] keytool failed - aborting.
        exit /b 1
    )

    (
        echo storeFile=release.keystore.jks
        echo storePassword=!STORE_PASS!
        echo keyAlias=!KEY_ALIAS!
        echo keyPassword=!KEY_PASS!
    ) > "%KEYSTORE_PROPS%"

    echo 1 > "%VERSIONCODE_FILE%"

    echo [build] Keystore created: %KEYSTORE_FILE%
    echo [build] Passwords saved in: %KEYSTORE_PROPS%
    echo [build] BACK UP the whole "keystore" folder somewhere safe now.
    echo [build] Losing it means you can never sign an update with the
    echo [build] same key again.
) else (
    echo [build] Reusing existing keystore: %KEYSTORE_FILE%
)

rem -------------------------------------------------------------
rem 2. Auto-incrementing versionCode
rem -------------------------------------------------------------
set /p VERSION_CODE=<"%VERSIONCODE_FILE%" 2>nul
if "%VERSION_CODE%"=="" set VERSION_CODE=1
set /a NEXT_VERSION_CODE=VERSION_CODE+1
echo %NEXT_VERSION_CODE% > "%VERSIONCODE_FILE%"

echo [build] versionName=%VERSION_NAME%  versionCode=%VERSION_CODE%

rem -------------------------------------------------------------
rem 3. Build every requested flavor
rem -------------------------------------------------------------
set TASKS=
for %%F in (%FLAVORS:,= %) do (
    if /i "%%F"=="playstore" (
        set TASKS=!TASKS! bundlePlaystoreRelease
    ) else (
        call :ToUpperFirst %%F FLAVOR_CAP
        set TASKS=!TASKS! assemble!FLAVOR_CAP!Release
    )
)

echo [build] Running: gradlew.bat!TASKS! -PappVersionName=%VERSION_NAME% -PappVersionCode=%VERSION_CODE%
call "%SCRIPT_DIR%gradlew.bat"!TASKS! -PappVersionName=%VERSION_NAME% -PappVersionCode=%VERSION_CODE%
if errorlevel 1 (
    echo [build] Gradle build failed.
    exit /b 1
)

rem -------------------------------------------------------------
rem 4. Collect the APKs/AABs
rem -------------------------------------------------------------
set OUT_DIR=%SCRIPT_DIR%releases\%VERSION_NAME%
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

for %%F in (%FLAVORS:,= %) do (
    if /i "%%F"=="playstore" (
        for /d %%D in ("%SCRIPT_DIR%leankeykeyboard\build\outputs\bundle\playstore*") do (
            for %%A in ("%%D\*.aab") do (
                if exist "%%A" copy /y "%%A" "%OUT_DIR%\" >nul
            )
        )
    ) else (
        rem With two flavor dimensions the output folder is named after
        rem the distribution+locale combo (e.g. originEn, originEnUk...),
        rem not just "origin" - match every locale variant for it.
        for /d %%D in ("%SCRIPT_DIR%leankeykeyboard\build\outputs\apk\%%F*") do (
            for %%A in ("%%D\release\*.apk") do (
                if exist "%%A" copy /y "%%A" "%OUT_DIR%\" >nul
            )
        )
    )
)

echo.
echo [build] Done. Files copied to: %OUT_DIR%
dir /b "%OUT_DIR%"
exit /b 0

:ToUpperFirst
setlocal
set "S=%~1"
set "FIRST=%S:~0,1%"
set "REST=%S:~1%"
rem upper-case just the first letter via PowerShell (robust vs. batch quirks)
for /f "usebackq delims=" %%C in (`powershell -NoProfile -Command "'%FIRST%'.ToUpper()"`) do set "FIRST=%%C"
endlocal & set "%~2=%FIRST%%REST%"
goto :eof
