package com.couponwatch.detector.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.data.repo.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 自动测试运行状态，供界面与悬浮球展示。 */
data class AutoTestState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val index: Int = 0,
    val total: Int = 0,
    val success: Int = 0,
    val failure: Int = 0,
    val lastCode: String = "",
    val lastResult: String = "",
)

/**
 * 自动测试执行器。
 *
 * **作用域硬限制**：只操作本应用自己的 [com.couponwatch.detector.ui.MockTestActivity]
 * （通过 `packageName:id/...` 精确查找控件）。不会、也无法在没有目标控件的情况下
 * 操作任何第三方应用；一旦当前窗口不是本应用，立即中止并记录 NO_TARGET。
 *
 * 流程：找输入框 → ACTION_SET_TEXT → 找提交按钮 → ACTION_CLICK → 读结果文本 → 分类记录。
 * 失败（格式错误 / 不存在）自动进入下一条；支持暂停、停止与最大次数。
 */
class AutoTester(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val logs: LogRepository,
) {

    private val _state = MutableStateFlow(AutoTestState())
    val state: StateFlow<AutoTestState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    fun start(codes: List<String>, maxAttempts: Int, delayMs: Long, notFoundMode: Boolean) {
        if (job?.isActive == true) return
        val list = codes.take(maxAttempts.coerceIn(1, 200))
        if (list.isEmpty()) return

        stopRequested = false
        paused = false
        job = scope.launch {
            _state.value = AutoTestState(running = true, total = list.size)
            var success = 0
            var failure = 0

            for ((index, code) in list.withIndex()) {
                if (stopRequested) break
                while (paused && !stopRequested) delay(120)
                if (stopRequested) break

                _state.update {
                    it.copy(index = index + 1, lastCode = code, lastResult = "执行中…")
                }

                val outcome = runOnce(code)
                if (outcome == RESULT_OK) success++ else failure++
                _state.update {
                    it.copy(success = success, failure = failure, lastResult = outcome)
                }
                record(code, outcome)

                if (outcome == RESULT_NO_TARGET) break
                if (index < list.lastIndex) delay(delayMs.coerceAtLeast(0L))
            }

            _state.update { it.copy(running = false, paused = false) }
        }
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
        _state.update { it.copy(running = false, paused = false) }
    }

    // ------------------------------------------------------------------ //

    private suspend fun runOnce(code: String): String {
        val input = findById(ID_INPUT) ?: return RESULT_NO_TARGET

        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return RESULT_SET_TEXT_FAILED
        }
        delay(120)

        val submit = findById(ID_SUBMIT) ?: return RESULT_NO_BUTTON
        submit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(260)

        val text = findById(ID_RESULT)?.text?.toString().orEmpty()
        return when {
            text.contains("格式正确") -> RESULT_OK
            text.contains("不存在") -> RESULT_NOT_FOUND
            text.contains("格式错误") -> RESULT_FORMAT_ERROR
            text.isEmpty() -> RESULT_NO_RESULT
            else -> RESULT_UNKNOWN
        }
    }

    /** 只按 `本应用包名:id/...` 查找，天然限定在自家页面内。 */
    private fun findById(viewId: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != service.packageName) return null
        val fullId = "${service.packageName}:id/$viewId"
        return root.findAccessibilityNodeInfosByViewId(fullId)?.firstOrNull()
    }

    private fun record(code: String, outcome: String) {
        val entity = DetectionLogEntity(
            timestamp = System.currentTimeMillis(),
            packageName = service.packageName,
            appLabel = "内置模拟页面",
            action = "TEST",
            score = 0,
            keywords = "",
            windowTitle = "自动测试",
            windowType = "MOCK",
            eventType = "AUTO_TEST",
            nodeSummary = "输入：$code",
            ocrUsed = false,
            controlTypes = "EditText,Button,TextView",
            testResult = outcome,
        )
        scope.launch {
            runCatching { logs.insert(entity) }
        }
    }

    companion object {
        private const val ID_INPUT = "mock_input"
        private const val ID_SUBMIT = "mock_submit"
        private const val ID_RESULT = "mock_result"

        const val RESULT_OK = "FORMAT_OK"
        const val RESULT_NOT_FOUND = "NOT_FOUND"
        const val RESULT_FORMAT_ERROR = "FORMAT_ERROR"
        const val RESULT_NO_TARGET = "NO_TARGET"
        const val RESULT_NO_BUTTON = "NO_BUTTON"
        const val RESULT_SET_TEXT_FAILED = "SET_TEXT_FAILED"
        const val RESULT_NO_RESULT = "NO_RESULT"
        const val RESULT_UNKNOWN = "UNKNOWN"
    }
}
