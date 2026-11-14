@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================================
echo  CouponWatch APK 构建脚本
echo ============================================================

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 java。
    echo        请安装 JDK 17 并设置 JAVA_HOME，例如：
    echo          setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.12+7"
    echo        或直接用 Android Studio 打开本目录构建。
    pause
    exit /b 1
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%v
echo [1/4] JDK: !JAVA_VER!

if "%ANDROID_HOME%"=="" if "%ANDROID_SDK_ROOT%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
    )
)
if "%ANDROID_HOME%"=="" (
    echo [警告] 未检测到 ANDROID_HOME / ANDROID_SDK_ROOT。
    echo        请先安装 Android SDK Platform 36 与 Build-Tools 36，
    echo        并设置：setx ANDROID_HOME "C:\Users\你的用户名\AppData\Local\Android\Sdk"
)
if not "%ANDROID_HOME%"=="" (
    echo [2/4] SDK: %ANDROID_HOME%
    if not exist "%ANDROID_HOME%\platforms\android-36" (
        echo [警告] 未找到 %ANDROID_HOME%\platforms\android-36
        echo        请用 SDK Manager 安装 "Android 16 (API 36)" 平台。
    )
)

if exist "gradlew.bat" (
    set GRADLE_CMD=gradlew.bat
    echo [3/4] 使用项目自带 Gradle Wrapper
) else (
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo [错误] 既没有 gradlew.bat，也没有全局 gradle。
        echo        二选一：
        echo          A. 用 Android Studio 打开本目录，Sync 后 Build -^> Build APK(s^)
        echo          B. 装 Gradle 8.13 后执行：gradle wrapper --gradle-version 8.13
        pause
        exit /b 1
    )
    echo [3/4] 未找到 Wrapper，改用全局 gradle，并生成 Wrapper
    call gradle wrapper --gradle-version 8.13
    set GRADLE_CMD=gradlew.bat
)

echo [4/4] 开始构建 lite 与 full 两个变体...
call %GRADLE_CMD% --no-daemon assembleLiteDebug assembleFullDebug
if errorlevel 1 (
    echo.
    echo [失败] 构建未通过，请查看上方 Gradle 输出。
    echo        常见原因：SDK 36 未安装、JDK 版本不是 17、网络无法访问 maven.google.com
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  构建成功，产物：
echo ============================================================
for %%f in (
    "app\build\outputs\apk\lite\debug\app-lite-debug.apk"
    "app\build\outputs\apk\full\debug\app-full-debug.apk"
) do (
    if exist %%f (
        for %%s in (%%f) do echo   %%~ff  ^(%%~zs 字节^)
    ) else (
        echo   [缺失] %%f
    )
)

echo.
echo 安装到已连接设备：
echo   adb install -r app\build\outputs\apk\lite\debug\app-lite-debug.apk
echo.
echo 首次开启无障碍前，记得在「应用信息 -^> 右上角菜单 -^> 允许受限设置」放行。
echo 详细步骤见 docs\verification-checklist.md
echo ============================================================
pause
endlocal
