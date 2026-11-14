package com.couponwatch.detector

import android.app.Application
import android.content.Context
import com.couponwatch.detector.data.db.CouponDatabase
import com.couponwatch.detector.data.prefs.SettingsStore
import com.couponwatch.detector.data.repo.FilterRepository
import com.couponwatch.detector.data.repo.FormatRepository
import com.couponwatch.detector.data.repo.LogRepository
import com.couponwatch.detector.notify.NotificationHelper

class CouponWatchApp : Application() {

    val database: CouponDatabase by lazy { CouponDatabase.get(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val logRepository: LogRepository by lazy { LogRepository(database.detectionLogDao()) }
    val filterRepository: FilterRepository by lazy { FilterRepository(database.appFilterDao()) }
    val formatRepository: FormatRepository by lazy { FormatRepository(database.formatRuleDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.ensureChannel(this)
    }

    companion object {
        @Volatile
        private var instance: CouponWatchApp? = null

        fun from(context: Context): CouponWatchApp =
            context.applicationContext as CouponWatchApp

        fun instanceOrNull(): CouponWatchApp? = instance
    }
}

/** 便捷扩展：`context.couponApp.logRepository` */
val Context.couponApp: CouponWatchApp
    get() = CouponWatchApp.from(this)
