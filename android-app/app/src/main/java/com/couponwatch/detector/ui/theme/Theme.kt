package com.couponwatch.detector.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BrandBlue = Color(0xFF2F6FED)
private val BrandBlueDark = Color(0xFF8FB2FF)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFF),
    onPrimaryContainer = Color(0xFF0B2B66),
    secondary = Color(0xFF5B6B84),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF1F2733),
    surface = Color.White,
    onSurface = Color(0xFF1F2733),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF46505F),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF0B2B66),
    primaryContainer = Color(0xFF1B3E7A),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFB6C4DA),
    background = Color(0xFF11151C),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF1A2029),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF242C38),
    onSurfaceVariant = Color(0xFFB6C0CF),
    error = Color(0xFFF2B8B5),
)

@Composable
fun CouponWatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            if (activity != null) {
                val window = activity.window
                // Android 16（API 36）强制 edge-to-edge，系统栏透明 + 深浅色图标由这里控制
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
