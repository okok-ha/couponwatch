package com.couponwatch.detector.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.data.repo.LogRepository
import com.couponwatch.detector.format.CodeEnumerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** 单次批量试码的运行状态。 */
data class BatchState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val attempt: Int = 0,
    val total: Int = 0,
    val hits: Int = 0,
    val misses: Int = 0,
    val unknown: Int = 0,
    val lastCode: String = "",
    val lastVerdict: String = "",
    val targetApp: String = "",
    val hitsList: List<String> = emptyList(),
    val message: String = "",
) {
    fun progressLine(): String =
        "批量 ${attempt}/${total} · 命中 ${hits} · 无效 ${misses} · 未判定 ${unknown}"
}

/**
 * 跨应用批量试码执行器。
 *
 * 流程（每一条候选码）：
 * 1. 在前台**第三方**窗口里找输入框（跳过本应用自己的悬浮窗与设置页）；
 * 2. `ACTION_SET_TEXT` 写入候选码（不接受时退化剪贴板 + 粘贴）；
 * 3. 在同一窗口内按文案匹配找「兑换 / 提交 / 领取 / 确认」按钮并点击；
 * 4. 轮询窗口文本差异，把结果分成 **命中 / 无效 / 限流 / 未判定** 四类；
 * 5. 无效 → 清空输入框，推进下一条；命中 → 记录完整码，按配置决定是否继续；
 * 6. 命中限流 → 指数退避；连续限流达到上限直接停，避免把账号跑进风控。
 *
 * 目标 App 由第一次成功定位的窗口锁定，中途切到别的应用立即中止。
 */
class BatchTrialRunner(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val logs: LogRepository,
) {

    data class Config(
        val maxAttempts: Int = 50,
        val intervalMs: Long = 1200L,
        val jitterRatio: Double = 0.35,
        val resultTimeoutMs: Long = 2000L,
        val stopOnHit: Boolean = false,
        val strategy: CodeEnumerator.Strategy = CodeEnumerator.Strategy.SEQUENTIAL,
        val lockPackage: String = "",
        /** 开始后等用户切到目标 App 的宽限时间；等不到才报 NO_TARGET 中止。 */
        val waitTargetMs: Long = 15_000L,
    )

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    private val rnd = Random(System.currentTimeMillis())

    // ------------------------------------------------------------------ //
    // 生命周期
    // ------------------------------------------------------------------ //

    fun start(samples: List<String>, config: Config): String {
        if (job?.isActive == true) return "已在运行中"

        val plan = CodeEnumerator.plan(samples) ?: return "样例为空，无法归纳规律"
        val codes = CodeEnumerator.generate(plan, config.maxAttempts, config.strategy)
        if (codes.isEmpty()) return "没有可用的候选码"

        stopRequested = false
        paused = false
        job = scope.launch {
            _state.value = BatchState(
                running = true,
                total = codes.size,
                message = plan.describe().replace('\n', ' '),
            )
            var hits = 0
            var misses = 0
            var unknown = 0
            var rateLimitStreak = 0
            var backoff = config.intervalMs

            // 给用户留出"在设置页点开始 → 切到目标 App"的时间：等不到目标窗口再中止。
            val deadline = System.currentTimeMillis() + config.waitTargetMs
            while (!stopRequested && findTarget(config.lockPackage) == null &&
                System.currentTimeMillis() < deadline
            ) {
                _state.update { it.copy(message = "等待目标应用的输入框…") }
                delay(250)
            }

            for ((index, code) in codes.withIndex()) {
                if (stopRequested) break
                while (paused && !stopRequested) delay(150)
                if (stopRequested) break

                _state.update { it.copy(attempt = index + 1, lastCode = mask(code), lastVerdict = "执行中…") }

                val outcome = attempt(code, config)
                when (outcome.verdict) {
                    Verdict.SUCCESS -> {
                        hits++
                        rateLimitStreak = 0
                        backoff = config.intervalMs
                        _state.update {
                            it.copy(hits = hits, hitsList = it.hitsList + code, targetApp = outcome.appLabel)
                        }
                        record(outcome, code, Verdict.SUCCESS)
                        if (config.stopOnHit) break
                    }

                    Verdict.INVALID -> {
                        misses++
                        rateLimitStreak = 0
                        backoff = config.intervalMs
                        _state.update { it.copy(misses = misses, targetApp = outcome.appLabel) }
                        record(outcome, code, Verdict.INVALID)
                    }

                    Verdict.RATE_LIMIT -> {
                        rateLimitStreak++
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                        _state.update {
                            it.copy(unknown = unknown, message = "命中限流，退避 ${backoff}ms 后继续")
                        }
                        record(outcome, code, Verdict.RATE_LIMIT)
                        if (rateLimitStreak >= MAX_RATE_LIMIT_STREAK) {
                            _state.update { it.copy(message = "连续 $rateLimitStreak 次限流，已停止") }
                            break
                        }
                    }

                    Verdict.NO_TARGET, Verdict.NO_INPUT, Verdict.NO_BUTTON,
                    Verdict.SET_TEXT_FAILED, Verdict.SWITCHED_APP -> {
                        // 结构性失败：界面不在预期状态，继续跑只会全错，直接中止
                        unknown++
                        _state.update {
                            it.copy(unknown = unknown, message = outcome.verdict.label)
                        }
                        record(outcome, code, outcome.verdict)
                        break
                    }

                    Verdict.UNKNOWN -> {
                        unknown++
                        _state.update { it.copy(unknown = unknown, targetApp = outcome.appLabel) }
                        record(outcome, code, Verdict.UNKNOWN)
                    }
                }

                _state.update { it.copy(lastVerdict = outcome.verdict.label) }

                if (index < codes.lastIndex) {
                    val wait = withJitter(backoff, config.jitterRatio)
                    delay(wait)
                }
            }

            _state.update {
                it.copy(running = false, paused = false, lastVerdict = if (it.lastVerdict == "执行中…") "已结束" else it.lastVerdict)
            }
        }
        return "已开始：${codes.size} 条候选"
    }

    fun pause() {
        paused = true
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        paused = false
        _state.update { it.copy(paused = false) }
    }

    fun stop() {
        stopRequested = true
        paused = false
        job?.cancel()
        _state.update { it.copy(running = false, paused = false, lastVerdict = "已停止") }
    }

    fun isRunning(): Boolean = job?.isActive == true

    // ------------------------------------------------------------------ //
    // 单次尝试
    // ------------------------------------------------------------------ //

    private data class Outcome(
        val verdict: Verdict,
        val appLabel: String = "",
        val packageName: String = "",
        val evidence: String = "",
    )

    private suspend fun attempt(code: String, config: Config): Outcome {
        val target = findTarget(config.lockPackage) ?: return Outcome(Verdict.NO_TARGET)
        if (config.lockPackage.isNotEmpty() && target.packageName != config.lockPackage) {
            return Outcome(Verdict.SWITCHED_APP, target.appLabel, target.packageName)
        }

        val input = target.input
        val inputKey = nodeKey(input)
        val wrote = writeText(input, code)
        if (!wrote) return Outcome(Verdict.SET_TEXT_FAILED, target.appLabel, target.packageName)
        delay(SETTLE_MS)

        val button = findSubmitButton(target.root) ?: return Outcome(
            Verdict.NO_BUTTON, target.appLabel, target.packageName
        )

        // 点击前快照，点击后比对新增文本，避免把弹窗里残留的旧文案当成结果
        val before = snapshotPackage(target.packageName) ?: emptySet()
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return Outcome(Verdict.NO_BUTTON, target.appLabel, target.packageName)
        }

        val verdict = awaitVerdict(target.packageName, before, config.resultTimeoutMs)
        if (verdict != Verdict.SUCCESS) clearInput(target.packageName, inputKey)
        return Outcome(verdict, target.appLabel, target.packageName, verdict.name)
    }

    private data class TargetWindow(
        val root: AccessibilityNodeInfo,
        val input: AccessibilityNodeInfo,
        val packageName: String,
        val appLabel: String,
    )

    private fun findTarget(lockPackage: String): TargetWindow? {
        val windows = try {
            service.windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val ordered = windows.sortedByDescending { runCatching { it.layer }.getOrDefault(0) }
        for (window in ordered) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == service.packageName) continue
            if (lockPackage.isNotEmpty() && pkg != lockPackage) continue

            val editables = ArrayList<AccessibilityNodeInfo>()
            collect(root) { node ->
                val cls = node.className?.toString().orEmpty()
                if ((node.isEditable || cls.contains("EditText") || cls.contains("AutoCompleteTextView")) &&
                    !node.isPassword
                ) editables.add(node)
            }
            // 优先拿已经聚焦的输入框（用户自己点开的兑换框）
            val input = editables.firstOrNull { it.isFocused } ?: editables.firstOrNull() ?: continue
            return TargetWindow(root, input, pkg, labelOf(pkg))
        }

        // 兜底：活动窗口
        val active = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val pkg = active.packageName?.toString() ?: return null
        if (pkg == service.packageName) return null
        if (lockPackage.isNotEmpty() && pkg != lockPackage) return null
        val editables = ArrayList<AccessibilityNodeInfo>()
        collect(active) { node ->
            val cls = node.className?.toString().orEmpty()
            if ((node.isEditable || cls.contains("EditText") || cls.contains("AutoCompleteTextView")) &&
                !node.isPassword
            ) editables.add(node)
        }
        val input = editables.firstOrNull { it.isFocused } ?: editables.firstOrNull() ?: return null
        return TargetWindow(active, input, pkg, labelOf(pkg))
    }

    private fun writeText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // 自定义输入框不吃 ACTION_SET_TEXT 时退化到粘贴
        return try {
            val cm = service.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("code", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            false
        }
    }

    /** 清空刚才写入的那个输入框；只清身份一致的节点，避免把手机号/验证码框一起抹掉。 */
    private fun clearInput(pkg: String, key: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val roots = packageRoots(pkg).ifEmpty { return }
        var cleared = false
        for (root in roots) {
            collect(root) { node ->
                if (cleared) return@collect
                val editable = node.isEditable || node.className?.toString().orEmpty().contains("EditText")
                if (!editable || node.isPassword) return@collect
                if (key.isNotEmpty() && nodeKey(node) != key) return@collect
                if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
                        .getOrDefault(false)
                ) cleared = true
            }
            if (cleared) return
        }
        // 身份对不上时退一步：只清当前聚焦的那个框
        if (!cleared) {
            for (root in roots) {
                collect(root) { node ->
                    if (!cleared && node.isFocused && node.isEditable) {
                        cleared = runCatching {
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }.getOrDefault(false)
                    }
                }
                if (cleared) return
            }
        }
    }

    /** 节点身份：类名 + 资源 id + 屏幕矩形，用来区分同一页面上的多个输入框。 */
    private fun nodeKey(node: AccessibilityNodeInfo): String = runCatching {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        "${node.className}|${node.viewIdResourceName}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }.getOrDefault("")

    private suspend fun awaitVerdict(pkg: String, before: Set<String>, timeoutMs: Long): Verdict {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSnapshot: Set<String>? = null
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val after = snapshotPackage(pkg) ?: return Verdict.SWITCHED_APP
            lastSnapshot = after
            // 一级：只看本次提交“新增”的文本，命中率最高、误判最少。
            val fresh = after - before
            if (fresh.isNotEmpty()) {
                val verdict = classify(fresh.joinToString(" | "))
                if (verdict != Verdict.UNKNOWN) return verdict
            }
        }
        // 二级兜底：很多 App 每次失败都弹同一句提示，文本从头到尾没变过，
        // 差集永远是空 —— 这时用当前整屏文本再判一次。
        // SUCCESS 不接受兜底结果：上一轮残留的成功文案不能算到这一条头上。
        val whole = lastSnapshot?.joinToString(" | ") ?: return Verdict.UNKNOWN
        val fallback = classify(whole)
        return if (fallback == Verdict.SUCCESS) Verdict.UNKNOWN else fallback
    }

    /** 该包名下所有窗口（含弹窗/浮层）里的全部文本，弹 Toast 也能被看到。 */
    private fun snapshotPackage(pkg: String): Set<String>? {
        val roots = packageRoots(pkg)
        if (roots.isEmpty()) return null
        val out = LinkedHashSet<String>(96)
        roots.forEach { snapshotTexts(it, out) }
        return out
    }

    private fun packageRoots(pkg: String): List<AccessibilityNodeInfo> {
        val windows = try {
            service.windows ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        windows.sortedByDescending { runCatching { it.layer }.getOrDefault(0) }.forEach { w ->
            val r = runCatching { w.root }.getOrNull() ?: return@forEach
            if (r.packageName?.toString() == pkg) roots.add(r)
        }
        if (roots.isEmpty()) {
            val active = runCatching { service.rootInActiveWindow }.getOrNull()
            if (active?.packageName?.toString() == pkg) roots.add(active)
        }
        return roots
    }

    /** 结果判定交给纯逻辑分类器，便于单测。 */
    private fun classify(text: String): Verdict = ResultClassifier.classify(text)

    private fun findSubmitButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        collect(root) { node ->
            if (!node.isClickable || node == root) return@collect
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val score = ResultClassifier.submitScore(text)
            if (score >= 0) candidates.add(node to score)
        }
        return candidates.minByOrNull { it.second }?.first
    }


    private fun snapshotTexts(root: AccessibilityNodeInfo, out: MutableSet<String>) {
        collect(root) { node ->
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
    }

    /** 迭代 BFS 遍历，限制节点数，避免超大树把主线程拖死。 */
    private inline fun collect(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            runCatching { action(node) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
    }

    private fun withJitter(baseMs: Long, ratio: Double): Long {
        if (baseMs <= 0) return 0
        val spread = (baseMs * ratio).toLong().coerceAtLeast(1)
        return (baseMs + rnd.nextLong(-spread, spread)).coerceAtLeast(200L)
    }

    private fun labelOf(packageName: String): String = try {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun mask(code: String): String =
        if (code.length > 4) code.take(2) + "***" + code.takeLast(2) else "***"

    private fun record(outcome: Outcome, code: String, verdict: Verdict) {
        val entity = DetectionLogEntity(
            timestamp = System.currentTimeMillis(),
            packageName = outcome.packageName.ifBlank { service.packageName },
            appLabel = outcome.appLabel.ifBlank { "批量试码" },
            action = "BATCH",
            score = 0,
            keywords = "",
            windowTitle = "批量试码",
            windowType = "BATCH_TRIAL",
            eventType = "AUTO_SUBMIT",
            nodeSummary = "候选：${mask(code)}（${code.length} 位）→ ${verdict.label}",
            controlTypes = "EditText,Button",
            testResult = if (verdict == Verdict.SUCCESS) code else verdict.name,
        )
        scope.launch { runCatching { logs.insert(entity) } }
    }

    companion object {
        private const val SETTLE_MS = 180L
        private const val POLL_MS = 160L
        private const val MAX_NODES = 500
        private const val MAX_BACKOFF_MS = 12_000L
        private const val MAX_RATE_LIMIT_STREAK = 3
        // 关键词表统一放在 ResultClassifier（纯 Kotlin、可单测）
    }
}
