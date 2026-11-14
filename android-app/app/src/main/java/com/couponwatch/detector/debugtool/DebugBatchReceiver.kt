package com.couponwatch.detector.debugtool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.couponwatch.detector.CouponWatchApp
import com.couponwatch.detector.service.CouponAccessibilityService

/**
 * 仅用于本机联调的广播入口（没有 UI 时也能起停批量试码、读运行状态）。
 *
 * ```
 * adb shell am broadcast -a com.couponwatch.detector.DEBUG_BATCH_START \
 *     --es samples "CODE1,CODE2" --ei max 40 --ei interval 500
 * adb shell am broadcast -a com.couponwatch.detector.DEBUG_BATCH_STOP
 * adb shell am broadcast -a com.couponwatch.detector.DEBUG_BATCH_STATE
 * ```
 *
 * 输出统一走 logcat，tag = CouponWatchBatch。
 *
 * 注意：这个类只在 debug 构建里通过 `app/src/debug/AndroidManifest.xml` 注册，
 * release 包里没有入口（类本身留在 main 源集，不影响正式功能）。
 */
class DebugBatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = CouponAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "无障碍服务未连接，忽略 ${intent.action}")
            return
        }
        when (intent.action) {
            ACTION_START -> {
                val samples = intent.getStringExtra("samples").orEmpty()
                    .split('\n', ',', '，', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                val app = context.applicationContext as CouponWatchApp
                val cfg = app.settings.current()
                val message = service.startBatch(
                    samples = samples,
                    maxAttempts = intent.getIntExtra("max", cfg.batchMax),
                    intervalMs = intent.getIntExtra("interval", cfg.batchIntervalMs.toInt()).toLong(),
                    stopOnHit = intent.getBooleanExtra("stopOnHit", cfg.batchStopOnHit),
                )
                Log.i(TAG, "start -> $message (samples=${samples.size})")
            }

            ACTION_STOP -> {
                service.stopBatch()
                Log.i(TAG, "stop")
            }

            ACTION_STATE -> {
                val s = CouponAccessibilityService.batchState.value
                Log.i(
                    TAG,
                    "state running=${s.running} paused=${s.paused} " +
                        "${s.attempt}/${s.total} hits=${s.hits} misses=${s.misses} " +
                        "unknown=${s.unknown} last=${s.lastCode} verdict=${s.lastVerdict} " +
                        "app=${s.targetApp} msg=${s.message} hitsList=${s.hitsList}",
                )
            }
        }
    }

    companion object {
        private const val TAG = "CouponWatchBatch"
        const val ACTION_START = "com.couponwatch.detector.DEBUG_BATCH_START"
        const val ACTION_STOP = "com.couponwatch.detector.DEBUG_BATCH_STOP"
        const val ACTION_STATE = "com.couponwatch.detector.DEBUG_BATCH_STATE"
    }
}
