package com.couponwatch.detector.ocr

import android.content.Context
import android.graphics.Bitmap

/**
 * lite 变体：不引入 ML Kit，OCR 恒为不可用。
 * 保持与 full 变体完全相同的公开 API，业务代码无需条件编译。
 */
object OcrEngineFactory {

    const val COMPILED: Boolean = false

    fun create(context: Context): OcrEngine = NoOpOcrEngine

    private object NoOpOcrEngine : OcrEngine {
        override val available: Boolean = false
        override val label: String = "OCR 未编译（lite 变体）"
        override suspend fun recognize(bitmap: Bitmap): String? = null
        override fun close() = Unit
    }
}
