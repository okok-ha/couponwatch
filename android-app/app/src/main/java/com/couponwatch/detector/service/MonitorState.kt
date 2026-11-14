package com.couponwatch.detector.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 监测状态中枢：悬浮球、无障碍服务、界面三方共享同一份状态。
 *
 * 语义：
 * - RUNNING：正常记录
 * - PAUSED：不记录，但服务仍在监听（切换成本低）
 * - STOPPED：不记录，悬浮球显示为停止态
 */
object MonitorState {

    enum class Status(val label: String) {
        RUNNING("监测中"),
        PAUSED("已暂停"),
        STOPPED("已停止"),
    }

    private val _status = MutableStateFlow(Status.RUNNING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _detections = MutableStateFlow(0)
    val detections: StateFlow<Int> = _detections.asStateFlow()

    private val _lastSummary = MutableStateFlow("")
    val lastSummary: StateFlow<String> = _lastSummary.asStateFlow()

    /** 无障碍服务是否已连接（用于悬浮球提示） */
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    val isRecording: Boolean
        get() = _status.value == Status.RUNNING

    fun setStatus(status: Status) {
        _status.value = status
    }

    fun togglePause() {
        _status.value = when (_status.value) {
            Status.RUNNING -> Status.PAUSED
            Status.PAUSED -> Status.RUNNING
            Status.STOPPED -> Status.RUNNING
        }
    }

    fun stop() {
        _status.value = Status.STOPPED
    }

    fun start() {
        _status.value = Status.RUNNING
    }

    fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
    }

    fun noteDetection(summary: String) {
        _detections.value = _detections.value + 1
        _lastSummary.value = summary
    }

    fun resetCounters() {
        _detections.value = 0
        _lastSummary.value = ""
    }
}
