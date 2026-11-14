package com.couponwatch.detector.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.couponwatch.detector.ui.theme.CouponWatchTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 强制 edge-to-edge，这里显式声明，布局统一用 WindowInsets 处理安全区
        enableEdgeToEdge()

        val initialTab = intent?.getIntExtra(EXTRA_OPEN_TAB, TAB_HOME) ?: TAB_HOME

        setContent {
            CouponWatchTheme {
                CouponWatchRoot(initialTab = initialTab)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_HOME = 0
        const val TAB_LOGS = 1
        const val TAB_FORMAT = 2
        const val TAB_FILTERS = 3
        const val TAB_SELF_CHECK = 4

        fun intentFor(context: android.content.Context, tab: Int): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, tab)
    }
}
