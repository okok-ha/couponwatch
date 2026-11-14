# 兑换码界面检测助手（CouponWatch）

Android 16 原生 APP（Kotlin + Jetpack Compose）。一个常驻悬浮球 + 无障碍服务，识别前台出现的「兑换码 / 兑换券 / 激活码」弹窗并记录出现与消失的时间线；在此之上提供两条操作链路：

- **手动填入**：把一条码填进当前前台的输入框（不点提交）。
- **批量试码（自动提交）**：给几条样例，归纳出规律，按节奏批量生成 → 填入 → 点提交 → 读结果 → 换下一条，命中或熔断即停。

所有界面操作都走无障碍的 `ACTION_SET_TEXT` / `ACTION_CLICK`，**不注入手势、不 root、不联网**（manifest 里没有 `INTERNET` 权限）。

---

## 一、当前状态

| 项目 | 状态 |
|---|---|
| 工程 | Android Studio 工程（Gradle Kotlin DSL，`lite` / `full` 双变体） |
| 规模 | **44 个 .kt / 约 6.4k 行** / 10 个 XML（含 `mocktarget/` 沙箱目标 App） |
| 编译 | ✅ `BUILD SUCCESSFUL`（AGP 8.10.1 · Kotlin 2.1.20 · JDK 17 · SDK 36） |
| 单元测试 | ✅ **35 个用例，0 失败**（`CodeEnumeratorTest` 10 / `ResultClassifierTest` 6 / `FormatAnalyzerTest` 8 / `RedactorTest` 8 / `CsvExportTest` 3） |
| 产物 | `lite` **11.2 MB**（无 OCR）· `full` **56.0 MB**（含 ML Kit 离线 OCR 模型） |
| 真机安装 | ✅ vivo V2339A（Android 16 / API 36）安装 `Success`，无障碍 `Bound services` 已确认 |
| 真机验收 | ✅ 批量试码全链路（沙箱目标 App 13/20 · 命中 1 · 无效 12 · 未判定 0）· 悬浮球批量按钮 · mock 页；⏸ 真实第三方 App、pause/resume 未验 |

> 诚实标注：生成逻辑（`CodeEnumerator`）、判定逻辑（`ResultClassifier`）、整条链路都已在真机上跑通；但目标 App 是自建沙箱（`mocktarget/`），真实发码 App 的界面与文案仍需再校一遍 —— 见 6.5。

---

## 二、能力清单

| # | 能力 | 说明 |
|---|---|---|
| 1 | 前台弹窗识别 | 扫描所有交互窗口，按评分模型判定"这是不是一个兑换弹窗"，阈值可调 |
| 2 | 悬浮球 | 56dp 圆球，可拖动；单击展开面板（监测状态 + 填入 + 批量 + 启动/暂停/停止 + 关闭） |
| 3 | 时间线日志 | 每次「出现 / 消失」一条记录：时间、包名、评分、命中关键词、窗口类型、控件类型、脱敏摘要 |
| 4 | 导出 CSV | UTF-8 BOM，Excel 直接打开不乱码 |
| 5 | 格式规则归纳 | 3 条以上样例 → 长度、字符集、前后缀、分隔符、正则、自匹配率 |
| 6 | 内置模拟页 | 自带"兑换弹窗"页面，验检测链路和自动测试不用第三方 App |
| 7 | 自动测试（本页） | 只按 `本包名:id/mock_input` / `mock_submit` / `mock_result` 操作，不碰第三方 |
| 8 | 跨应用手动填入 | 单次：把一条码写进前台输入框，**不点提交** |
| 9 | **批量试码** | 样例 → 规律 → 批量生成 → 逐条填入 → 点提交 → 读结果 → 清空换下一条 |
| 10 | 自检页 | 五项能力自检，用来判断"是不是目标应用不暴露节点" |

---

## 三、构建

### 3.1 工具链（本机实际安装）

| 组件 | 版本 / 路径 |
|---|---|
| JDK | Microsoft OpenJDK **17.0.13+11** · `C:/android-toolchain/jdk/jdk-17.0.13+11` |
| Android SDK | `C:/android-toolchain/sdk`（platform **android-36**、build-tools **36.0.0**） |
| Gradle | **8.13** · `C:/android-toolchain/gradle/gradle-8.13` |
| AGP / Kotlin / Compose BOM | 8.10.1 / 2.1.20 / 2025.05.00 |
| Room / KSP | 2.7.0 / 2.1.20-1.0.31 |
| adb | `D:/xiazai/platform-tools/adb.exe`（1.0.41 / 37.0.1） |
| 测试机 | vivo V2339A（iQOO Neo9 Pro），Android 16 / API 36 |

`local.properties` 必须用正向斜杠：

```properties
sdk.dir=C:/android-toolchain/sdk
```

### 3.2 命令

```bat
set JAVA_HOME=C:\android-toolchain\jdk\jdk-17.0.13+11
set ANDROID_HOME=C:\android-toolchain\sdk

gradle.bat --no-daemon --console=plain testLiteDebugUnitTest assembleLiteDebug assembleFullDebug
```

### 3.3 产物

| 变体 | 路径 | 大小 |
|---|---|---|
| lite | `app/build/outputs/apk/lite/debug/app-lite-debug.apk` | 11.2 MB |
| full | `app/build/outputs/apk/full/debug/app-full-debug.apk` | 56.0 MB |

单元测试 HTML 报告：`app/build/reports/tests/testLiteDebugUnitTest/index.html`

---

## 四、安装与授权

三条 adb 快捷命令（完整步骤见 [`docs/install-guide.md`](docs/install-guide.md)）：

```bat
adb install -r app\build\outputs\apk\lite\debug\app-lite-debug.apk
adb shell pm grant com.couponwatch.detector.debug android.permission.POST_NOTIFICATIONS
adb shell appops set com.couponwatch.detector.debug SYSTEM_ALERT_WINDOW allow
```

**无障碍服务必须手动开**：设置 → 无障碍 → 已下载的服务 → 兑换码界面检测助手 → 开启。
Android 13+ 侧载应用无障碍开关默认灰色，需要先"应用信息 → ⋮ → 允许受限设置"。
在 vivo 上用 `settings put secure enabled_accessibility_services` 直写会被 ROM 回滚，实测无效。

---

## 五、五个页面

| 页面 | 作用 |
|---|---|
| 首页 | 服务状态、通知/悬浮窗授权、悬浮球开关、**跨应用填入（手动）**、**批量试码（自动提交）**、检测参数、关键词、快捷操作 |
| 日志 | 时间线列表、筛选、清空、导出 CSV |
| 格式 | 样例 → 归纳规则 → 保存/启用/删除 |
| 过滤 | 黑白名单模式 + 应用勾选（白名单模式下一个不勾 = 不监测任何应用） |
| 自检 | 服务是否连上、可读窗口数、事件掩码、标志位、最近事件 |

---

## 六、跨应用填入与批量试码

### 6.1 手动填入（单次，不提交）

首页「跨应用填入（手动）」：粘贴/保存一条码 → 点「填入前台」。只做 `ACTION_SET_TEXT`（失败退化为剪贴板 + `ACTION_PASTE`），**不点任何按钮**。

### 6.2 批量试码（自动填入 + 自动提交）

**输入**：几条码样例，一行一条，例如

```
SAVE-2026-8F3K
SAVE-2026-QW7M
SAVE-2026-4XZ9
```

**流程**（`BatchTrialRunner`）：

1. `CodeEnumerator.plan(samples)` 逐列比对，定出**固定位**与**可变位**、每位的字母表、是否带校验位（Luhn / Damm / Verhoeff / MOD97）。
2. 按 `SEQUENTIAL`（最右位先加、逐位进位）或 `RANDOM`（带 seed 去重）生成候选，上限 `batchMax`。
3. 逐条执行：
   - `findTarget()`：按窗口层级降序找当前前台的可编辑输入框（跳过密码框），锁定该输入框的"key"（class | resource-id | 屏幕矩形）；
   - `ACTION_SET_TEXT` 写入；失败退化剪贴板 + `ACTION_PASTE`；
   - `SETTLE_MS=180` 后点击提交按钮（文案命中 `立即兑换 / 确认兑换 / 兑换 / 提交 / 领取 / 确定 / 确认 / 激活 / 使用 / redeem / submit / confirm / apply / activate`，按顺序取分最高者）；
   - 提交前给该包名所有窗口的文本拍快照，提交后每 `POLL_MS=160` 轮询 diff，最多等 `resultTimeoutMs=2000`；
   - 判定：**限流 → 失败 → 成功** 的顺序（详见 6.3）；
   - 非命中则清空那个输入框（只清 key 相同的一个框，不会连手机号/验证码框一起抹掉），换下一条。
4. 节奏：每条之间 `batchIntervalMs`（默认 1200ms）± `35%` 抖动；命中限流时指数退避（上限 12s），连续 3 次限流即停；结构性失败（找不到窗口/输入框/按钮、应用被切走）立即中止。
5. 结果：命中的码进日志（`action=BATCH`、`windowType=BATCH_TRIAL`、`testResult` 存完整码），其余只存判定标签。

**参数**：`batchMax`（1–5000，默认 50）、`batchIntervalMs`（200–60000，默认 1200）、`batchStopOnHit`（命中即停，默认关）。

#### 6.2.1 两条真实样例的实测归纳

样例（24 位）：`DAB6PMJD7B78JIEDMJSGQED8`、`DAB6PQ3D7B78JIEDOHF0HSF3`。

| 项 | 值 |
|---|---|
| 长度 | 24 |
| 模板 | `DAB6P##D7B78JIED########` |
| 固定位 | 14（`DAB6P` + 中间 `D7B78JIED`） |
| 可变位 | 10（下标 5、6、16–23） |
| 字母表 | 每列统一 36（`0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ`） |
| 校验位 | 无（非纯数字，Luhn/Damm/Verhoeff/MOD97 均不成立） |
| 候选空间 | 36^10 = 3,656,158,440,062,976（≈3.7×10¹⁵） |
| 默认策略 | `RANDOM`（空间 > 20 万自动切随机） |

要点：

- 中间 `D7B78JIED` 两条完全一致，几乎不可能是巧合，按**固定段**处理；若再有第三条样例在此段不同，`plan()` 会自动把它变成可变位。
- 第 6 位（`J`/`3`）和第 19 位（`G`/`0`）在两条样例里字母、数字都出现过，说明这段是**字母数字混排**。一旦任意一列混排，`plan()` 会把其余可变列一并放宽到 36 进制——否则"恰好都是字母"的列会永远枚举不到数字，覆盖率被砍掉一大半（这条有单测 `混排片段会把整段可变列放宽到字母数字` 守着）。
- 顺序模式下生成的是样例邻域：`…MJSGQED8` → `…MJSGQED9` → `…MJSGQEDA` → `…MJSGQEDB`…；随机模式是全域散点，例如 `DAB6PWVD7B78JIEDDWFZL91Q`、`DAB6P7GD7B78JIED0WIGC1AO`。

> 现实约束：10 位 36 进制 ≈ 3.7×10¹⁵ 个可能值，随机采样基本不可能撞中。真正有意义的两种场景是：①发码方按序列递增发放（那就用顺序模式从最近的有效码往后推，邻域命中率高）；②同一批码只有末尾几位不同（用顺序模式把这一段穷举，几十万条以内可行）。哪一种都不是"无限期随机撞"，所以悬浮球和首页都只按 `batchMax` 跑一轮，跑完就停，不循环。

### 6.3 判定关键词（`ResultClassifier`）

| 判定 | 关键词 |
|---|---|
| `RATE_LIMIT`（限流） | 频繁、稍后再试、too many、slow down、操作过快 |
| `INVALID`（无效） | 无效、不存在、错误、失败、已被使用、已使用、过期、已失效、不正确、已领取、invalid、not found、expired、used、error、wrong；否定前缀：未成功、未能、无法兑换 |
| `SUCCESS`（命中） | 兑换成功、领取成功、已到账、已发放、恭喜、success、redeemed、accepted |

判定顺序固定：先看限流，再看否定前缀/失败词（避免"兑换失败"里出现"兑换成功"被误判为成功），最后才看成功词。

### 6.4 行为边界

| 做 | 不做 |
|---|---|
| 枚举当前前台可见窗口的节点 | 不注入手势 / 不模拟触摸事件 |
| `ACTION_SET_TEXT` + `ACTION_CLICK` | 不 root、不 hook、不读别的应用数据 |
| 文本 diff 判结果 | 不带 `INTERNET`，不上传任何内容 |
| 命中码原文进本机日志 | 不绕过验证码、不在被限流后继续硬撞 |
| 密码框整段跳过 | 不保存截图 |

### 6.5 真机联调（沙箱目标 App + debug 广播）

没有真实发码 App 也能跑通全链路：

1. `mocktarget/` 是独立 Gradle 模块（包名 `com.couponwatch.mocktarget`），一个兑换码输入框 + 「立即兑换」+ 结果/计数文本。判定规则 `code.sumOf { it.code } % 11 == 0`，成功文案「兑换成功，奖励已到账」，失败文案「兑换码无效，请检查后重试」。装法：`gradle.bat :mocktarget:assembleDebug` 后 `adb install -r mocktarget\build\outputs\apk\debug\mocktarget-debug.apk`。
2. `debugtool/DebugBatchReceiver` 只在 debug 构建注册，可以不开 UI 直接起停批量：
   ```bat
   adb shell am broadcast -n com.couponwatch.detector.debug/com.couponwatch.detector.debugtool.DebugBatchReceiver ^
     -a com.couponwatch.detector.DEBUG_BATCH_START ^
     --es samples "CODE1,CODE2" --ei max 40 --ei interval 400
   adb shell am broadcast -n com.couponwatch.detector.debug/com.couponwatch.detector.debugtool.DebugBatchReceiver ^
     -a com.couponwatch.detector.DEBUG_BATCH_STATE
   adb logcat -s CouponWatchBatch
   ```
   必须显式 `-n` 指定组件，隐式广播投递不到（Android 14+）。
3. 2026-11-14 18:30 实测结果（vivo V2339A / Android 16）：13/20，`命中 1 · 无效 12 · 未判定 0`，命中码 `DAB6PYFD7B78JIED1M8ITTZS` 以原文写进 `detection_logs`（`action=BATCH`）。悬浮球面板同步显示 `批量试码 · 已结束 批量 13/20 · 命中 1 · 无效 12 · 未判定 0`。

> 判定为什么不再是"看文本差异"这么简单：很多 App 每次失败都回同一句话，差集永远为空。现在一级看新增文本，超时后用整屏文本兜底（但 `SUCCESS` 不接受兜底，避免把上一轮的残留成功文案算到这一条头上）。

---

## 七、已知限制

1. `FLAG_SECURE` 窗口（银行、支付、部分密码页）节点为空，读不到，**不做绕过**。
2. 纯 `SurfaceView` / `TextureView` / 游戏引擎 / 部分 Flutter 界面不暴露节点，只能靠 `full` 变体的 OCR 补（默认关闭 `canTakeScreenshot`）。
3. WebView 与 Compose 界面 `resource-id` 常为空，找输入框只能靠 `className`/`isEditable`/`isFocused`。
4. 批量试码的判定靠"文本 diff + 关键词"，如果目标弹窗的成功提示是一张图片或动画，会判成 `UNKNOWN`——此时状态栏会累计 unknown，需要人为介入。
5. 有些应用提交后不换文案只清空输入框，会被判 `INVALID`；调 `resultTimeoutMs` 或换判定关键词可缓解。
6. 侧载受限设置、厂商后台限制、服务被系统回收，都会导致"看起来没工作"，先看自检页五项。

---

## 八、目录结构（节选）

```
android-app/
├── app/src/main/java/com/couponwatch/detector/
│   ├── CouponWatchApp.kt                 # Application：DB / prefs / 仓库装配
│   ├── data/
│   │   ├── db/                           # Room：日志、格式规则、应用过滤
│   │   ├── prefs/SettingsStore.kt        # SharedPreferences 读写（含批量试码参数）
│   │   └── repo/                         # LogRepository / FormatRepository / FilterRepository
│   ├── format/
│   │   ├── CodeFormatAnalyzer.kt         # 样例 → 正则/字段
│   │   ├── CodeEnumerator.kt             # 样例 → 可变位/字母表/校验位 → 批量候选
│   │   └── TestDataFactory.kt            # 按规则变异造测试数据
│   ├── service/
│   │   ├── CouponAccessibilityService.kt # 无障碍服务，事件入口 + 批量/填入调度
│   │   ├── CouponDetectionEngine.kt      # 指纹 + TTL 状态机，出 APPEAR/DISAPPEAR
│   │   ├── WindowScanner.kt              # 窗口 → DialogSignal（评分模型）
│   │   ├── CrossAppFiller.kt             # 单次填入
│   │   ├── BatchTrialRunner.kt           # 批量试码执行器
│   │   ├── BatchVerdict.kt               # Verdict 枚举 + ResultClassifier（纯逻辑，可单测）
│   │   └── AutoTester.kt                 # 仅本应用 mock 页的自动测试
│   ├── overlay/FloatingBallService.kt    # 悬浮球 + 面板
│   ├── ui/                               # Compose：Home/Logs/Format/Filters/SelfCheck
│   └── util/Redactor.kt                  # 掩码与截断
├── app/src/lite|full/java/.../OcrEngineFactory.kt
├── app/src/test/java/.../                # 35 个 JVM 单测
└── docs/                                 # 安装教程、编译报告、限制说明、验收清单
```

---

## 九、文档索引

| 文档 | 内容 |
|---|---|
| [`docs/install-guide.md`](docs/install-guide.md) | 安装、三项授权、无障碍开启、模拟页验收、adb 速查、常见问题 |
| [`docs/build-report.md`](docs/build-report.md) | 工具链版本、构建日志、真实修复的编译问题、真机验证记录 |
| [`docs/limitations.md`](docs/limitations.md) | 哪类界面读不到、OCR 能补什么、厂商限制 |
| [`docs/verification-checklist.md`](docs/verification-checklist.md) | 验收清单 |
| [`docs/android16-capability-report.md`](docs/android16-capability-report.md) | Android 16 无障碍能力边界 |
| [`../HANDOFF-2026-11-14.md`](../HANDOFF-2026-11-14.md) | 交接包：实现细节、踩坑、下一步 |
