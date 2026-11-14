package com.couponwatch.detector.ocr

import android.graphics.Bitmap

/**
 * OCR 辅助引擎（可选模块）。
 *
 * 只在 `full` 变体里提供真实实现（ML Kit 文字识别），`lite` 变体提供一个空实现，
 * 这样核心检测链路不依赖任何 OCR 库，APK 也能小几 MB。
 *
 * 注意：真正做"屏幕取词"还需要无障碍服务声明 `android:canTakeScreenshot="true"`
 * （见 README「可选：启用 OCR」），本工程默认不开启该能力，避免静默截屏。
 */
interface OcrEngine {

    /** 当前变体是否真的具备 OCR 能力。 */
    val available: Boolean

    /** 用于界面展示的实现描述。 */
    val label: String

    /**
     * 识别位图中的文字。
     *
     * @return 识别到的整段文本；不可用或失败时返回 null。
     */
    suspend fun recognize(bitmap: Bitmap): String?

    fun close()
}
