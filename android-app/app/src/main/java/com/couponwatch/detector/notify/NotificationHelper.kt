package com.couponwatch.detector.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.couponwatch.detector.R
import com.couponwatch.detector.data.db.DetectionLogEntity
import com.couponwatch.detector.ui.MainActivity
import com.couponwatch.detector.util.TimeFormat

/**
 * 检测提醒。
 *
 * 只做"本地提醒"：不带远程推送、不申请任何额外权限。
 * 同一应用 10 秒内最多一条，避免连续窗口变化把通知栏刷满。
 */
object NotificationHelper {

    const val CHANNEL_ID = "coupon_detection"
    private const val THROTTLE_MS = 10_000L
    private const val NOTIFICATION_ID_BASE = 1000

    private val lastNotifyAt = mutableMapOf<String, Long>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun notifyDetection(context: Context, log: DetectionLogEntity): Boolean {
        if (!canPost(context)) return false

        val now = System.currentTimeMillis()
        val last = lastNotifyAt[log.packageName] ?: 0L
        if (now - last < THROTTLE_MS) return false
        lastNotifyAt[log.packageName] = now

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_LOGS)
        }
        val pending = PendingIntent.getActivity(
            context,
            log.packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val keywordText = log.keywords.ifBlank { "未命中关键词" }
        val title = "检测到疑似兑换弹窗"
        val body = buildString {
            append(log.appLabel)
            append(" · 评分 ").append(log.score)
            append(" · ").append(keywordText)
            append(" · ").append(TimeFormat.clock(log.timestamp))
        }

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()

        return try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + (log.packageName.hashCode() and 0xFF), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun clearAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
