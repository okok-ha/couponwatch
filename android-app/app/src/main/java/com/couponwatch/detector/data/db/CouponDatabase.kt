package com.couponwatch.detector.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DetectionLogEntity::class, AppFilterEntity::class, FormatRuleEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class CouponDatabase : RoomDatabase() {

    abstract fun detectionLogDao(): DetectionLogDao

    abstract fun appFilterDao(): AppFilterDao

    abstract fun formatRuleDao(): FormatRuleDao

    companion object {
        private const val DB_NAME = "coupon_watch.db"

        @Volatile
        private var instance: CouponDatabase? = null

        fun get(context: Context): CouponDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CouponDatabase::class.java,
                    DB_NAME,
                )
                    // 这是纯本地日志库，升级时不做破坏性迁移；后续版本如改表结构再加 Migration
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
