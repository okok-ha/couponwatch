package com.couponwatch.detector.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.data.repo.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 跨应用填入器：把**用户自己提供的一个兑换码**填进前台应用当前聚焦的输入框。
 *
 * 能力边界（刻意设计，不是没做完）：
 * 1. **单次、手动**：每次只在用户点悬浮球「填入」时执行一次，没有循环、没有批量；
 * 2. **只填不提交**：绝不调用第三方应用的提交/兑换按钮，是否提交由用户自己决定；
 * 3. **不猜码**：填入内容来自用户显式设置的字符串，不由本类生成；
 * 4. **跳过密码框**：`isPassword=true` 的节点直接排除；
 * 5. **不填自己**：目标包名等于本应用时直接返回 SAME_APP。
 *
 * 这样既能覆盖"把自己的券码填进购物 App 兑换框"的正常需求，也不会变成批量试码工具。
 */
class CrossAppFiller(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val logs: LogRepository,
) {

    data class Target(
        val packageName: String,
        val appLabel: String,
        val node: AccessibilityNodeInfo,
        val hint: String,
        val focused: Boolean,
    )

    enum class Result(val label: String) {
        NO_TARGET("未找到可填入的输入框"),
        SAME_APP("目标是本应用，已跳过"),
        EMPTY_CODE("待填入内容为空"),
        FILLED("已填入"),
        FILL_FAILED("填入失败（控件不接受文本）"),
    }

    /** 探测当前前台应用里最合适的输入框（不写入）。 */
    fun probe(): Target? {
        // 1) 按窗口层级从高到低找，跳过本应用自己的悬浮窗
        val windows = try {
            service.windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        windows.sortedByDescending { runCatching { it.layer }.getOrDefault(0) }
            .forEach { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@forEach
                probeRoot(root)?.let { return it }
            }

        // 2) 兜底：活动窗口
        val active = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        return active?.let { probeRoot(it) }
    }

    private fun probeRoot(root: AccessibilityNodeInfo): Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == service.packageName) return null

        val editables = ArrayList<AccessibilityNodeInfo>()
        collectEditables(root, editables, limit = 40)
        val usable = editables.filter { !it.isPassword }
        if (usable.isEmpty()) return null

        val node = usable.firstOrNull { it.isFocused } ?: usable.first()
        return Target(
            packageName = pkg,
            appLabel = labelOf(pkg),
            node = node,
            hint = (node.hintText?.toString() ?: node.text?.toString() ?: "").take(24),
            focused = node.isFocused,
        )
    }

    /** 执行一次填入。 */
    fun fill(code: String): Pair<Result, Target?> {
        val text = code.trim()
        if (text.isEmpty()) return Result.EMPTY_CODE to null

        val target = probe() ?: return Result.NO_TARGET to null
        if (target.packageName == service.packageName) return Result.SAME_APP to target

        target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var ok = target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!ok) {
            // 部分自定义输入框不接受 ACTION_SET_TEXT，退化为剪贴板粘贴
            ok = try {
                val clipboard = service.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("code", text))
                target.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
                false
            }
        }

        val result = if (ok) Result.FILLED else Result.FILL_FAILED
        record(target, text, result)
        return result to target
    }

    // ------------------------------------------------------------------ //

    private fun collectEditables(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        limit: Int,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 150 && out.size < limit) {
            val node = queue.removeFirst()
            visited++
            val cls = node.className?.toString().orEmpty()
            if (node.isEditable || cls.contains("EditText") || cls.contains("AutoCompleteTextView")) {
                out.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
    }

    private fun labelOf(packageName: String): String = try {
        val pm = service.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun record(target: Target, code: String, result: Result) {
        val masked = if (code.length > 4) code.take(2) + "***" + code.takeLast(2) else "***"
        val entity = DetectionLogEntity(
            timestamp = System.currentTimeMillis(),
            packageName = target.packageName,
            appLabel = target.appLabel,
            action = "FILL",
            score = 0,
            keywords = "",
            windowTitle = target.hint,
            windowType = "CROSS_APP_FILL",
            eventType = "MANUAL_FILL",
            nodeSummary = "填入内容：$masked（${code.length} 位）",
            controlTypes = "EditText",
            testResult = result.name,
        )
        scope.launch { runCatching { logs.insert(entity) } }
    }
}
