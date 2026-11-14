# 验证清单（Android 16 真机）

> 目标：用 6 个阶段、每阶段一个可观测结果，确认「跨应用界面变化 → 弹窗识别 → 本地记录 → 通知」整条链路在**你自己的 Android 16 设备**上成立。
> 任何一步不符合预期，直接跳到最后的「故障定位表」。

---

## 阶段 0：构建 APK

```bat
build_apk.bat
```

预期输出末尾出现两行（路径以本机为准）：

```
app\build\outputs\apk\lite\debug\app-lite-debug.apk
app\build\outputs\apk\full\debug\app-full-debug.apk
```

- 只验证检测链路 → 用 **lite**（不含 ML Kit，体积小）
- 需要 OCR 兜底 → 用 **full**

---

## 阶段 1：安装并确认包名

```bat
adb devices
adb install -r app\build\outputs\apk\lite\debug\app-lite-debug.apk
adb shell pm list packages | findstr couponwatch
```

预期：`package:com.couponwatch.detector.debug`（debug 变体带 `.debug` 后缀）。

---

## 阶段 2：允许受限设置 + 开启无障碍

1. 打开 APP（图标名「兑换弹窗检测」）。
2. 首页点「去开启无障碍服务」。
3. 若开关为灰色/提示不可用：
   **设置 → 应用 → 兑换弹窗检测 → ⋮ → 允许受限设置**，再回到无障碍页。
4. 找到「兑换弹窗检测」→ 打开。

命令行核对：

```bat
adb shell settings get secure enabled_accessibility_services
```

预期输出包含 `com.couponwatch.detector.debug/com.couponwatch.detector.service.CouponAccessibilityService`。

```bat
adb shell dumpsys accessibility | findstr /i couponwatch
```

预期能看到该服务与其 capabilities/flags 片段。

---

## 阶段 3：能力自检（关键一步）

APP 底部导航 →「自检」→ 右上角刷新。

逐项核对：

| 检查项 | 预期值 | 不达标说明 |
|---|---|---|
| 系统已启用 | 是 | 阶段 2 未完成 |
| 进程内已连接 | 是 | 服务被系统回收或崩溃，看 `logcat` |
| 订阅事件 | 含 `TYPE_WINDOW_STATE_CHANGED` + `TYPE_WINDOWS_CHANGED` | xml 配置未生效 |
| 可读取窗口内容 | `true` | `canRetrieveWindowContent` 未设 true |
| 交互窗口标志 | `true` | 缺 `flagRetrieveInteractiveWindows` |
| **当前可见窗口数** | **> 0** | 为 0 说明 `getWindows()` 拿不到窗口，跨应用识别无从谈起 |
| 通知权限 | `true` | 未授予 `POST_NOTIFICATIONS` |
| OCR 模块 | lite/full 与实际变体一致 | 装错变体 |

日志侧核对：

```bat
adb logcat -c
adb logcat -s CouponWatchService:V
```

预期看到一行 `connected: TYPE_WINDOW_STATE_CHANGED + TYPE_WINDOWS_CHANGED + TYPE_WINDOW_CONTENT_CHANGED`。

---

## 阶段 4：内置模拟页面（原生弹窗）

1. 首页 →「打开内置模拟页面」。
2. 点「显示原生弹窗」（含真实 `EditText` + 「立即兑换」按钮）。
3. 立刻切到「日志」页。

预期：
- 「自检」页「接收事件」「扫描次数」持续增长；
- 1 秒内出现一条 `出现` 记录，评分为 8 分左右（关键词 3 + 输入框 2 + 按钮 2 + 弹窗特征 1）；
- 通知栏出现「检测到疑似兑换弹窗」。

4. 点「取消」关闭弹窗。
5. 预期 3.5 秒内出现一条 `消失` 记录。

---

## 阶段 5：WebView 弹窗 + 自动演示

1. 模拟页面点「显示 Web 弹窗」。
2. 预期同样生成 `出现` 记录（评分通常 6 分：关键词 3 + 强词组 2 + 弹窗特征 1）。
3. 点「自动演示」：每 2.5 秒切换一次，共 8 次。
4. 预期日志页出现 4 组 `出现/消失` 交替记录。

> 这一步验证的是"网页控件不一定暴露为 EditText"时，评分仍能靠关键词命中——这正是把关键词权重设成最高的原因。

---

## 阶段 6：过滤、导出、清理

1. 「过滤」页搜索任意应用 → 打开右侧开关（=排除）→ 让该应用弹一个弹窗 → 预期不再记录。
2. 「日志」页右上角「分享」图标 → 选择保存位置 → 得到 UTF-8 BOM 的 CSV，Excel 可直接打开且中文不乱码。
3. 「日志」页右上角「删除」图标 → 清空。
4. 首页把「判定阈值」拉到 12 → 再弹一次 → 预期不再记录（评分达不到阈值），拉回 5 后恢复。

---

## 故障定位表

| 现象 | 最可能原因 | 处理 |
|---|---|---|
| 无障碍开关灰色不可点 | 侧载受限设置 | 应用信息 → ⋮ → 允许受限设置 |
| 「进程内已连接」为否但系统已启用 | 服务被系统杀掉 / 启动崩溃 | `adb logcat -s CouponWatchService:V AndroidRuntime:E` 看堆栈 |
| 「当前可见窗口数」恒为 0 | 缺 `flagRetrieveInteractiveWindows`，或被 ROM 限制 | 检查 `accessibility_service_config.xml`；换一台原生系统验证 |
| 事件数增长但没有记录 | 评分未达阈值 | 首页把阈值降到 3 再试；或补充关键词 |
| 有记录但没有通知 | 通知权限/渠道被关 | 系统设置 → 通知 → 兑换弹窗检测；自检页看「通知权限」 |
| WebView 弹窗不触发但原生弹窗触发 | 网页控件无 `EditText` 类名 | 属预期，关键词仍应命中；若未命中，检查页面文本是否含"兑换码" |
| 关闭弹窗后没有「消失」记录 | 后续无事件且 tick 未跑 | 等 4 秒；仍无则看服务是否被回收 |
| `build_apk.bat` 报找不到 java | 未装 JDK 17 | 装 JDK 17 并配 `JAVA_HOME`，或直接用 Android Studio 构建 |

---

## 隐私自查（建议顺手做一遍）

1. 「日志」页任意记录，确认界面摘要里的长数字已被掩码（如 `13*********`）。
2. 在任意应用里输入密码（`isPassword=true` 的输入框）→ 预期日志里**完全看不到**该字段文本。
3. 手机开飞行模式 → 全部功能（检测、记录、导出、OCR）仍可用 → 证明无网络依赖。
4. `adb shell dumpsys package com.couponwatch.detector.debug | findstr permission` → 只应看到 `POST_NOTIFICATIONS`。
