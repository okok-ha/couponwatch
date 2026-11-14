package com.couponwatch.detector.service

/** 一次扫描得到的"疑似弹窗"候选信号。 */
data class DialogSignal(
    val packageName: String,
    val appLabel: String,
    val windowId: Int,
    val windowTitle: String,
    val windowType: String,
    val score: Int,
    val keywords: List<String>,
    val summary: String,
    val hasEditableField: Boolean,
    val hasActionButton: Boolean,
    val nodeCount: Int,
    val controlTypes: String = "",
    /** 是否来自本应用的模拟页面（只有它允许在自家包名内被检测） */
    val isOwnMockPage: Boolean = false,
) {
    /** 用于判断"还是不是同一个弹窗"的指纹。 */
    fun signature(): String = "$packageName|$windowTitle|$windowType"
}

/** 运行期统计，供"自检"页面展示。 */
data class EngineStats(
    val events: Int = 0,
    val windowStateEvents: Int = 0,
    val windowsChangedEvents: Int = 0,
    val contentChangedEvents: Int = 0,
    val scans: Int = 0,
    val appear: Int = 0,
    val disappear: Int = 0,
    val lastEvent: String = "",
    val lastPackage: String = "",
    val lastScore: Int = 0,
    val lastKeywords: String = "",
    val capability: String = "",
)
