package com.couponwatch.detector.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * full 变体：ML Kit 端上文字识别（模型随 APK 打包，离线可用，不联网）。
 */
object OcrEngineFactory {

    const val COMPILED: Boolean = true

    fun create(context: Context): OcrEngine = MlKitOcrEngine()
}

private class MlKitOcrEngine : OcrEngine {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override val available: Boolean = true
    override val label: String = "ML Kit 文字识别（full 变体，离线）"

    override suspend fun recognize(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.text)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    override fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
    }
}
