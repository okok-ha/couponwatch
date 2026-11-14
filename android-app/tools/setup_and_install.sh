#!/bin/bash
# 一键：解压工具链 → 安装 SDK 组件 → 构建 APK → 安装到手机 → 开启无障碍 → 验证
# 用法：bash tools/setup_and_install.sh [lite|full]
set -o pipefail

FLAVOR="${1:-lite}"
TC=/c/android-toolchain
DL=$TC/downloads
JDK_ROOT=$TC/jdk
SDK=$TC/sdk
GRADLE_ROOT=$TC/gradle
ADB=/d/xiazai/platform-tools/adb.exe
APP="/c/Users/1/WorkBuddy AI/2026-11-14-12-09-34/android-app"
LOG=$TC/setup.log
PKG="com.couponwatch.detector.debug"
SVC="$PKG/com.couponwatch.detector.service.CouponAccessibilityService"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
die() { log "FAIL: $*"; exit 1; }

: > "$LOG"
log "=== 1/7 解压 JDK ==="
if [ ! -d "$JDK_ROOT" ] || [ -z "$(ls -A "$JDK_ROOT" 2>/dev/null)" ]; then
    mkdir -p "$JDK_ROOT"
    python -m zipfile -e "$DL/jdk17.zip" "$JDK_ROOT" || die "解压 JDK 失败"
fi
JAVA_HOME=$(find "$JDK_ROOT" -maxdepth 2 -type d -name bin | head -1 | xargs -r dirname)
[ -f "$JAVA_HOME/bin/java.exe" ] || die "找不到 java.exe（JAVA_HOME=$JAVA_HOME）"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
log "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java.exe" -version 2>&1 | head -2 | tee -a "$LOG"

log "=== 2/7 解压 cmdline-tools ==="
if [ ! -f "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/tmp"
    mkdir -p "$SDK/cmdline-tools/tmp"
    python -m zipfile -e "$DL/cmdline-tools.zip" "$SDK/cmdline-tools/tmp" || die "解压 cmdline-tools 失败"
    mv "$SDK/cmdline-tools/tmp/cmdline-tools" "$SDK/cmdline-tools/latest" 2>/dev/null \
        || mv "$SDK/cmdline-tools/tmp" "$SDK/cmdline-tools/latest"
    rm -rf "$SDK/cmdline-tools/tmp"
fi
[ -f "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ] || die "sdkmanager 不存在"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

log "=== 3/7 安装 SDK 组件（platform-36 / build-tools 36 / platform-tools）==="
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
if [ ! -d "$SDK/platforms/android-36" ]; then
    yes | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null 2>&1
    "$SDKM" --sdk_root="$SDK" "platforms;android-36" "build-tools;36.0.0" "platform-tools" 2>&1 | tail -20 | tee -a "$LOG"
fi
[ -d "$SDK/platforms/android-36" ] || die "platform-36 安装失败"
[ -d "$SDK/build-tools/36.0.0" ] || log "警告：未找到 build-tools/36.0.0"

log "=== 4/7 解压 Gradle ==="
GRADLE_BIN=$(find "$GRADLE_ROOT" -maxdepth 3 -name gradle.bat 2>/dev/null | head -1)
if [ -z "$GRADLE_BIN" ]; then
    mkdir -p "$GRADLE_ROOT"
    python -m zipfile -e "$DL/gradle.zip" "$GRADLE_ROOT" || die "解压 Gradle 失败"
    GRADLE_BIN=$(find "$GRADLE_ROOT" -maxdepth 3 -name gradle.bat 2>/dev/null | head -1)
fi
[ -n "$GRADLE_BIN" ] || die "找不到 gradle.bat"
log "gradle=$GRADLE_BIN"

log "=== 5/7 写 local.properties 并构建 $FLAVOR ==="
SDK_MIXED=$(cygpath -m "$SDK" 2>/dev/null || echo "$SDK")
printf 'sdk.dir=%s\n' "$SDK_MIXED" > "$APP/local.properties"  # 必须用正向斜杠，否则 Properties 会把 \a \s 当转义
case "$FLAVOR" in
    full) TASK="assembleFullDebug"; APK="$APP/app/build/outputs/apk/full/debug/app-full-debug.apk" ;;
    *)    TASK="assembleLiteDebug"; APK="$APP/app/build/outputs/apk/lite/debug/app-lite-debug.apk" ;;
esac
cd "$APP" || die "找不到工程目录"
"$GRADLE_BIN" --no-daemon --console=plain "$TASK" 2>&1 | tee -a "$LOG" | tail -40
[ -f "$APK" ] || die "构建失败，未生成 $APK"

log "=== 6/7 安装到设备 ==="
"$ADB" devices | tee -a "$LOG"
"$ADB" install -r "$APK" 2>&1 | tee -a "$LOG" | grep -E "Success|Failure|INSTALL_" || true
"$ADB" shell pm list packages 2>/dev/null | grep couponwatch | tee -a "$LOG"

log "=== 7/7 开启无障碍并启动 ==="
"$ADB" shell settings put secure enabled_accessibility_services "$SVC" 2>&1 | tee -a "$LOG"
"$ADB" shell settings put secure accessibility_enabled 1 2>&1 | tee -a "$LOG"
"$ADB" shell am start -n "$PKG/com.couponwatch.detector.ui.MainActivity" 2>&1 | tee -a "$LOG"
sleep 3
log "--- 服务连接日志 ---"
"$ADB" logcat -d -s CouponWatchService:V | tail -5 | tee -a "$LOG"
log "--- 已启用服务 ---"
"$ADB" shell settings get secure enabled_accessibility_services | tee -a "$LOG"
log "DONE"
