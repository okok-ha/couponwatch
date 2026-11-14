package com.couponwatch.detector.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.couponwatch.detector.util.Redactor

/**
 * 窗口扫描器：把"当前可见的窗口"翻译成若干 [DialogSignal]。
 *
 * 评分规则（可在设置里调阈值，默认 5 分）：
 * +3 每个命中关键词（最多 3 个，封顶 9 分）
 * +2 存在可编辑输入框（兑换码输入框的强特征）
 * +2 存在"兑换/领取/提交"类按钮
 * +1 窗口特征像弹窗（有标题，或面积小于屏幕 85%）
 * +2 文本中同时出现"兑换码/兑换券/激活码"这类明确词组
 *
 * 隐私约束：`isPassword` 的节点直接跳过，不读取其文本；所有进入摘要的文本
 * 都经 [Redactor] 脱敏与截断。
 */
class WindowScanner(
    private val context: Context,
    private val maxNodes: Int = 150,
) {

    private val labelCache = HashMap<String, String>()

    private val strongPhrases = listOf("兑换码", "兑换券", "激活码", "礼品卡", "coupon code", "gift code")
    private val actionWords = listOf("兑换", "领取", "提交", "确认", "立即", "redeem", "confirm", "apply")

    fun scan(
        service: AccessibilityService,
        keywords: List<String>,
        threshold: Int,
    ): List<DialogSignal> {
        val roots = collectRoots(service)
        if (roots.isEmpty()) return emptyList()

        val screenArea = screenArea()
        val results = ArrayList<DialogSignal>(roots.size)

        roots.forEach { candidate ->
            val signal = evaluate(
                candidate.root,
                candidate.window,
                keywords,
                threshold,
                screenArea,
            )
            if (signal != null) results.add(signal)
        }
        return results.sortedByDescending { it.score }
    }

    private data class RootCandidate(
        val root: AccessibilityNodeInfo,
        val window: AccessibilityWindowInfo?,
    )

    private fun collectRoots(service: AccessibilityService): List<RootCandidate> {
        val out = ArrayList<RootCandidate>()

        // 优先使用窗口列表：能拿到窗口类型、标题、层级，是识别"弹窗属于哪个窗口"的关键
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (windows.isNotEmpty()) {
            windows.forEach { window ->
                val root = try {
                    window.root
                } catch (e: Exception) {
                    null
                }
                if (root != null) out.add(RootCandidate(root, window))
            }
        }

        // 兜底：窗口列表为空（未授予交互窗口标志，或系统未返回）时用活动窗口
        if (out.isEmpty()) {
            val active = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            if (active != null) out.add(RootCandidate(active, null))
        }
        return out
    }

    private fun evaluate(
        root: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo?,
        keywords: List<String>,
        threshold: Int,
        screenArea: Int,
    ): DialogSignal? {
        val texts = ArrayList<String>(24)
        var hasEditable = false
        var hasActionButton = false
        var visited = 0
        val controlTypes = LinkedHashSet<String>()
        var hasMockViewId = false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++

            // 密码节点：整段跳过，绝不读取文本
            if (node.isPassword) continue

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                texts.add(text)
                val cls = node.className?.toString().orEmpty()
                if (cls.contains("Button") || cls.contains("ImageButton")) {
                    if (actionWords.any { text.lowercase().contains(it) }) hasActionButton = true
                }
            }
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty()) texts.add(desc)

            if (node.viewIdResourceName?.contains(":id/mock_") == true) hasMockViewId = true

            val cls = node.className?.toString().orEmpty()
            if (cls.contains("EditText") || cls.contains("AutoCompleteTextView")) hasEditable = true
            if (node.isEditable) hasEditable = true
            when {
                cls.contains("EditText") || cls.contains("AutoCompleteTextView") -> controlTypes.add("EditText")
                cls.contains("Button") -> controlTypes.add("Button")
                cls.contains("CheckBox") || cls.contains("Switch") -> controlTypes.add("CheckBox")
                cls.contains("ImageView") -> controlTypes.add("ImageView")
                cls.contains("RecyclerView") || cls.contains("ListView") -> controlTypes.add("列表")
                cls.contains("WebView") -> controlTypes.add("WebView")
                cls.contains("TextView") -> controlTypes.add("TextView")
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                }
                if (child != null) queue.addLast(child)
            }
        }

        if (texts.isEmpty()) return null

        val haystack = texts.joinToString(" ").lowercase()
        val matched = keywords.filter { it.isNotBlank() && haystack.contains(it.lowercase()) }
        if (matched.isEmpty()) return null

        var score = 0
        score += minOf(matched.size, 3) * 3
        if (hasEditable) score += 2
        if (hasActionButton) score += 2
        if (strongPhrases.any { haystack.contains(it) }) score += 2

        val windowTitle = window?.title?.toString()?.trim().orEmpty()
        val windowTypeName = windowTypeName(window?.type ?: -1)
        val isDialogLike = windowTitle.isNotEmpty() ||
            (window != null && windowAreaRatio(window, screenArea) < 0.85f)
        if (isDialogLike) score += 1

        if (score < threshold) return null

        val packageName = root.packageName?.toString()
            ?: window?.root?.packageName?.toString()
            ?: return null

        val summary = Redactor.summary(
            texts.asSequence()
                .mapNotNull { Redactor.text(it) }
                .distinct()
                .take(8)
                .toList()
        )

        return DialogSignal(
            packageName = packageName,
            appLabel = labelFor(packageName),
            windowId = window?.id ?: -1,
            windowTitle = Redactor.maskCodeLike(windowTitle).take(60),
            windowType = windowTypeName,
            score = score,
            keywords = matched.take(5),
            summary = summary,
            hasEditableField = hasEditable,
            hasActionButton = hasActionButton,
            nodeCount = visited,
            controlTypes = controlTypes.joinToString(","),
            isOwnMockPage = hasMockViewId,
        )
    }

    private fun windowAreaRatio(window: AccessibilityWindowInfo, screenArea: Int): Float {
        if (screenArea <= 0) return 1f
        val rect = Rect()
        window.getBoundsInScreen(rect)
        val area = rect.width().toLong() * rect.height().toLong()
        return (area.toFloat() / screenArea.toFloat()).coerceIn(0f, 1f)
    }

    private fun screenArea(): Int {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels * metrics.heightPixels
    }

    fun labelFor(packageName: String): String = labelCache.getOrPut(packageName) {
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    companion object {
        fun windowTypeName(type: Int): String = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "TYPE_APPLICATION"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "TYPE_INPUT_METHOD"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "TYPE_SYSTEM"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "TYPE_SPLIT_SCREEN_DIVIDER"
            -1 -> "UNKNOWN(无窗口对象)"
            else -> "TYPE_OTHER($type)"
        }

        /** 供自检页面展示：当前服务实际生效的事件类型。 */
        fun describeEventMask(eventTypes: Int): String {
            val names = buildList {
                if (eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0) {
                    add("TYPE_WINDOW_STATE_CHANGED")
                }
                if (eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0) {
                    add("TYPE_WINDOWS_CHANGED")
                }
                if (eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0) {
                    add("TYPE_WINDOW_CONTENT_CHANGED")
                }
                if (eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED != 0) {
                    add("TYPE_VIEW_CLICKED")
                }
            }
            return if (names.isEmpty()) "0x${Integer.toHexString(eventTypes)}" else names.joinToString(" + ")
        }

        fun describeFlags(flags: Int): String {
            val names = buildList {
                if (flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) {
                    add("FLAG_RETRIEVE_INTERACTIVE_WINDOWS")
                }
                if (flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0) {
                    add("FLAG_REPORT_VIEW_IDS")
                }
                if (flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS != 0) {
                    add("FLAG_INCLUDE_NOT_IMPORTANT_VIEWS")
                }
            }
            return if (names.isEmpty()) "0x${Integer.toHexString(flags)}" else names.joinToString(" + ")
        }
    }
}
