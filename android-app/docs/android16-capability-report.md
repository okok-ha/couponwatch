# Android 16（API 36）无障碍事件监听能力核查报告

> 核查时间：2026-11-14　目标平台：Android 16 / API 36　compileSdk=36、targetSdk=36、minSdk=26
>
> 本文只回答一个问题：**在 Android 16 上，"监听跨应用界面变化 → 识别弹窗"这条链路是否仍然成立？**
> 结论先给：**成立**。API 36 没有移除或收紧 `AccessibilityService` 的事件与窗口 API；Android 16 的两项强制变更（edge-to-edge、自适应布局）影响的是 UI 呈现，不影响事件监听。

---

## 一、结论摘要

1. **事件入口未变**：`AccessibilityService.onAccessibilityEvent(AccessibilityEvent)` 仍是唯一入口，事件类型仍由 `res/xml/accessibility_service_config.xml` 的 `android:accessibilityEventTypes` 声明。
2. **窗口级信息可用**：`getWindows()` / `AccessibilityWindowInfo`（含 `type`、`title`、`getBoundsInScreen`）在 API 36 上仍然可用，前提是声明 `flagRetrieveInteractiveWindows`。
3. **节点级信息可用**：`AccessibilityNodeInfo` 的 `text` / `contentDescription` / `className` / `isEditable` / `isPassword` / `childCount` 全部保留，`isPassword` 是"不采集密码"这条约束的技术抓手。
4. **Android 16 没有任何针对无障碍服务或通知的专项行为变更**（已逐条核对官方 `behavior-changes-16` 清单）。
5. **真正的坑不在 16，而在 13+**：侧载安装的 APK 默认处于"受限设置"，用户必须先「允许受限设置」才能打开无障碍开关；通知还需要 `POST_NOTIFICATIONS` 运行时授权。

---

## 二、能力矩阵

| 能力 | 相关 API / 属性 | 起始版本 | API 36 状态 | 本案用途 | 依据 |
|---|---|---|---|---|---|
| 事件回调 | `AccessibilityService.onAccessibilityEvent` | API 14 | 可用 | 唯一事件入口 | 官方无障碍服务指南 |
| 事件订阅声明 | `android:accessibilityEventTypes` | API 14 | 可用 | 只订阅 3 类窗口事件 | 官方指南原文："The events you want to receive." |
| 读取 UI 层级 | `android:canRetrieveWindowContent="true"` | API 14 | 可用 | 读取节点文本 | 官方指南原文："Must be `true` if your service needs to inspect the UI hierarchy" |
| 窗口集合事件 | `TYPE_WINDOWS_CHANGED` | API 21 | 可用 | 捕捉"新窗口出现" | `AccessibilityServiceInfo` 文档：该事件需 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` |
| 窗口列表 | `getWindows()` → `List<AccessibilityWindowInfo>` | API 21 | 可用 | 判断弹窗属于哪个窗口 | 同上，受同一 flag 控制 |
| 窗口属性 | `AccessibilityWindowInfo.getType()` / `getTitle()` / `getBoundsInScreen()` | API 21 / 24 / 21 | 可用 | 窗口类型、标题、面积占比 | API 参考 |
| 节点遍历 | `AccessibilityNodeInfo.getChild()` / `getChildCount()` | API 14 | 可用 | 收集界面文本与控件类型 | API 参考 |
| 密码识别 | `AccessibilityNodeInfo.isPassword()` | API 18 | 可用 | **跳过密码框，不读文本** | API 参考 |
| 可编辑控件 | `isEditable()` / `className` 含 `EditText` | API 18 | 可用 | "存在输入框"加分项 | API 参考 |
| 截屏（OCR 前置） | `CAPABILITY_CAN_TAKE_SCREENSHOT` + `android:canTakeScreenshot` | API 30 | 可用，但**默认不申请** | OCR 兜底 | API 参考 |
| 无障碍工具标记 | `android:isAccessibilityTool` | API 31 | 可用 | 如实声明 `false` | 声明 false 时系统会显示隐私提醒 |
| 通知权限 | `POST_NOTIFICATIONS` | API 33 | 必须运行时申请 | 弹窗提醒 | 平台权限模型 |
| 侧载受限设置 | 「允许受限设置」 | Android 13 | 必须用户手动放行 | 安装后开无障碍 | 见第五节 |

---

## 三、Android 16 行为变更的逐条影响

官方 `behavior-changes-16`（仅列 targetSdk ≥ 36 的变更）与本项目相关的只有两条，均**不涉及事件监听**：

### 1. Edge to edge opt-out going away

> "For apps targeting Android 16 (API level 36), `R.attr#windowOptOutEdgeToEdgeEnforcement` is deprecated and disabled, and your app can't opt-out of going edge-to-edge."

**影响**：界面必须自己处理系统栏安全区，否则内容会被状态栏/导航栏压住。
**本案处理**：`MainActivity` 调用 `enableEdgeToEdge()`，根布局用 `Scaffold` 的 `innerPadding` 让出安全区；`MockTestActivity` 同样处理。不依赖任何 `windowOptOutEdgeToEdgeEnforcement`。

### 2. Adaptive layouts

> "For apps targeting Android 16 (API level 36), orientation, resizability, and aspect ratio restrictions no longer apply on displays with smallest width >= 600dp."

**影响**：平板/折叠屏上 `screenOrientation` 会被忽略，界面可能被拉宽或分屏。
**本案处理**：不锁定方向；四个页面全部用可滚动 `Column` + `LazyColumn` + 底部导航，宽度自适应，不存在固定像素布局。

### 3. 与本项目无关的变更

`Health and fitness permissions`（健康类前台服务）、`Fixed rate work scheduling optimization`（`scheduleAtFixedRate` 补跑策略）均不涉及本工程——本工程不使用前台服务，也不使用定时任务调度。

### 4. 官方文档中**不存在**的变更

在 `behavior-changes-16` 全文的六个分类（用户体验与系统 UI、核心功能、设备形态、健康与健身、连接、安全、隐私）中，**没有以无障碍服务或通知为主题的条目**。也就是说：Android 15 上能用的无障碍监听方式，在 16 上同样能用。

---

## 四、必须注意的非 16 专属限制

| 限制 | 说明 | 本案对策 |
|---|---|---|
| `FLAG_SECURE` 窗口 | 银行/支付类页面禁止被无障碍读取，节点为空 | 扫描返回空即不记录，不做任何绕过 |
| WebView 内容 | 网页控件可能暴露为 `android.view.View` 而非 `EditText` | 评分以**关键词**为主（权重最高），控件特征只是加分项 |
| 部分 ROM 额外开关 | 小米/华为等有"无障碍快捷方式""后台限制"等自有开关 | 自检页给出可观测指标，便于判断是系统拦截还是代码问题 |
| 弹窗关闭无明确事件 | 关闭弹窗时系统未必给事件 | 引擎用 `ACTIVE_TTL_MS=3500ms` 超时兜底 + 2 秒定时 tick |
| 服务被系统回收 | 长时间空闲可能被解绑 | `onUnbind` 清理状态；重新开启后自动恢复，不依赖常驻进程 |

---

## 五、侧载安装的关键一步（Android 13+，含 16）

自建 APK 通过 `adb install` 或文件管理器安装后，系统会把它标记为"受限设置"：

- 直接去「设置 → 无障碍」会看到开关是灰的，或提示"出于安全考虑，此设置当前不可用"。
- 正确路径：**设置 → 应用 → 兑换弹窗检测 → 右上角 ⋮ → 允许受限设置** → 再回到无障碍页面开启。

这不是本工程的缺陷，是 Android 13 起对所有侧载应用的一致策略。使用 Android Studio 直接 Run 安装（debug 签名）时同样适用。

---

## 六、本次核查的边界（如实说明）

| 项目 | 状态 |
|---|---|
| 官方文档逐条核对 | ✅ 已完成（`behavior-changes-16`、无障碍服务指南） |
| API 名称/版本/属性核对 | ✅ 已完成，并写入代码与本文矩阵 |
| 工程静态校验（XML、资源引用、import 解析、括号配平、R 引用、版本目录） | ✅ 已通过（`tools/validate_project.py`） |
| **在真实 Android 16 设备上跑通事件回调** | ❌ **未完成** —— 本次开发环境没有 Android SDK / JDK / 真机 / 模拟器（详见 `README.md`「构建状态」） |
| APK 编译产物 | ❌ 未生成 —— 需要在本机装好 JDK 17 + SDK 36 后执行 `build_apk.bat` |

因此，本报告给出的是"**API 契约层面成立**"的结论 + 一套**可自证的运行时自检**：APP 内置「自检」页会直接读取 `AccessibilityServiceInfo` 的**实际生效值**（事件掩码、flags、`CAPABILITIES`、`getWindows()` 返回的窗口数、通知权限），任何人拿到 APK 后 30 秒内就能确认这条链路在自己的机器上是否真的通了，而不必相信文档。

---

## 七、在你自己的设备上完成验证

见 `docs/verification-checklist.md`，其中包含逐条勾选步骤与 `adb` 命令（含 `dumpsys accessibility`、`logcat` 过滤、组件启动命令）。
