# Android 16 安装与授权教程

> 目标设备：vivo V2339A（iQOO Neo9 Pro），Android 16 / API 36
> 通用步骤同样适用于其他 Android 13–16 机型，差异处会标注。

---

## 1. 安装 APK

### 方式 A：adb（推荐，最快）

```bat
adb devices
adb install -r app\build\outputs\apk\lite\debug\app-lite-debug.apk
```

看到 `Success` 即安装完成。包名：`com.couponwatch.detector.debug`（debug 变体带 `.debug` 后缀）。

### 方式 B：手机文件管理器

把 APK 拷到手机（微信文件传输 / USB 传输 / 网盘均可），点击安装。系统会提示"未知来源应用"，允许后继续。

> lite 11 MB，full 53 MB（含离线 OCR 模型）。日常用 lite 即可。

---

## 2. 通知权限（Android 13+ 必须）

打开 APP → 首页「授予通知权限」→ 允许。
若已误点拒绝：设置 → 应用 → 兑换码界面检测助手 → 通知 → 允许。

adb 快捷方式：

```bat
adb shell pm grant com.couponwatch.detector.debug android.permission.POST_NOTIFICATIONS
```

---

## 3. 悬浮窗权限（悬浮球必需）

**路径**：设置 → 应用 → 兑换码界面检测助手 → 其他权限 / 特殊权限 → **显示在其他应用上层** → 允许。

APP 首页也有「授权悬浮窗」按钮，点击会直接跳到该页面。

adb 快捷方式：

```bat
adb shell appops set com.couponwatch.detector.debug SYSTEM_ALERT_WINDOW allow
adb shell appops get com.couponwatch.detector.debug SYSTEM_ALERT_WINDOW
```

未授权时点「启动悬浮球」不会报错，但圆球不会出现——服务检测到无权限会自行退出并打一条 `FloatingBall` 日志。

---

## 4. 开启无障碍服务（关键一步）

**路径**：设置 → 无障碍 → 已下载的服务 / 已安装的服务 → **兑换码界面检测助手** → 开启。

### 如果开关是灰色的

Android 13 起，**侧载安装**（adb install 或文件管理器安装）的 APK 默认处于"受限设置"，无障碍开关会被禁用。需要先放行：

> 设置 → 应用 → 兑换码界面检测助手 → 右上角 **⋮** → **允许受限设置** → 返回无障碍页面再开启

这是系统对所有侧载应用的一致策略，不是本 APP 的缺陷。

### 关于 adb 直写设置

网上常见做法是 `adb shell settings put secure enabled_accessibility_services ...`。
**在本机 vivo 上实测无效**：写入后立即被系统回滚（`settings get` 仍是原值），这是厂商对 `secure` 表的保护。请老老实实走上面的 UI 路径，一次点击即可。

---

## 5. 启动悬浮球并验收

1. 首页 → 「启动悬浮球」→ 屏幕边缘出现蓝色圆球（约 56dp），通知栏出现常驻通知。
2. **拖动**：按住圆球移动，松手即停。
3. **单击**：展开面板，显示「监测中 · 无障碍已连接 · 已识别 N 次」与三个按钮。
4. **再单击圆球**：收起面板。
5. 面板按钮：
   - **启动**：恢复记录（圆球变蓝）
   - **暂停/继续**：暂停记录（圆球变黄，文字变「停」）
   - **停止**：停止记录（圆球变灰，文字变「止」）
   - **关闭悬浮球**：结束前台服务并移除圆球

---

## 6. 配置格式规则

1. 底部导航 → **格式**。
2. 在「样例」里输入 3 个以上兑换码样例（每行一个），例如：
   ```
   SAVE-2026-8F3K
   SAVE-2026-QW7M
   SAVE-2026-4XZ9
   ```
3. 点「分析」→ 看到长度、字符集、前缀、后缀、分隔符与生成的正则，以及自匹配率。
4. 点「保存并启用」→ 该规则成为当前规则（同一时刻只有一条启用）。
5. 已保存规则列表里可「启用」切换或「删除」。

> 格式匹配只代表字符串符合规则，**不代表兑换码真实有效**。

---

## 7. 验证检测链路（内置模拟页面）

1. 首页 → 「打开内置模拟页面」。
2. 点「显示兑换弹窗」→ 出现带输入框与「立即兑换」按钮的弹窗。
3. 切到「日志」页 → 1 秒内应出现一条 `出现` 记录，含：时间、包名、评分、命中关键词、窗口类型、**控件类型**。
4. 关闭弹窗 → 3.5 秒内出现一条 `消失` 记录。
5. 悬浮球面板上的「已识别 N 次」同步 +1。

---

## 8. 自动测试（只在本页面内执行）

1. 确认已启用一条格式规则（第 6 步）。
2. 模拟页面 → 「自动测试（无障碍驱动本页）」。
3. 页面下方实时显示：`运行中 · 3/10 · 成功 2 / 失败 1`，以及最近一条 `码 → 结果`。
4. 可选：打开「模拟服务器返回『不存在』」开关，验证"不存在"分支也能正确切换下一条。
5. 需要中止时点「停止自动测试」。

**作用域限制**：执行器只按 `本应用包名:id/mock_input`、`mock_submit`、`mock_result` 查找控件，一旦当前窗口不是本应用会立即返回 `NO_TARGET` 并停止。不会操作任何第三方应用。

---

## 9. 导出 CSV

日志页右上角「分享」图标 → 选择保存位置 → 生成 UTF-8 BOM 的 CSV（Excel 直接打开不乱码）。

列：`序号,时间,应用包名,应用名称,动作,评分,命中关键词,窗口标题,窗口类型,事件类型,控件类型,界面摘要,测试结果,OCR`

---

## 10. adb 命令速查

```bat
:: 安装 / 覆盖安装
adb install -r app\build\outputs\apk\lite\debug\app-lite-debug.apk

:: 权限
adb shell pm grant com.couponwatch.detector.debug android.permission.POST_NOTIFICATIONS
adb shell appops set com.couponwatch.detector.debug SYSTEM_ALERT_WINDOW allow

:: 启动悬浮球（等价于首页按钮）
adb shell am start-foreground-service -n com.couponwatch.detector.debug/com.couponwatch.detector.overlay.FloatingBallService

:: 打开主界面 / 模拟页面
adb shell am start -n com.couponwatch.detector.debug/com.couponwatch.detector.ui.MainActivity
adb shell am start -n com.couponwatch.detector.debug/com.couponwatch.detector.ui.MockTestActivity

:: 查看服务是否连上
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility | findstr /i couponwatch
adb logcat -s CouponWatchService:V FloatingBall:V

:: 卸载
adb uninstall com.couponwatch.detector.debug
```

---

## 11. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 无障碍开关灰色 | 侧载受限设置 | 应用信息 → ⋮ → 允许受限设置 |
| 圆球不出现 | 未授权悬浮窗 | 第 3 步；查 `adb logcat -s FloatingBall` |
| 有记录但无通知 | 通知权限被拒 / 渠道被关 | 第 2 步；系统设置里检查通知渠道 |
| 检测不到弹窗 | 阈值过高 / 该应用用自绘界面 | 首页把阈值降到 3；或参考 `docs/limitations.md` |
| 自动测试报 `NO_TARGET` | 当前不在模拟页面，或无障碍未连接 | 回到模拟页面并确认服务已开启 |
| 自动测试全失败 | 未启用格式规则 | 第 6 步保存并启用一条规则 |
| 日志为空 | 悬浮球处于「暂停/停止」 | 面板点「启动」，圆球应变蓝 |
