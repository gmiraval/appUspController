package com.uspcontroller.app.domain.model

/**
 * Represents the real-time device metrics polled from the USP Agent.
 *
 * Maps to TR-181 parameters:
 * - [cpuUsage]: Device.DeviceInfo.ProcessStatus.CPUUsage (percentage 0-100)
 * - [memoryTotal]: Device.DeviceInfo.MemoryStatus.Total (in KB)
 * - [memoryFree]: Device.DeviceInfo.MemoryStatus.Free (in KB)
 * - [wifiSsid]: Device.WiFi.SSID.1.SSID
 */
data class DeviceMetrics(
    val cpuUsage: Int,
    val memoryTotal: Long,
    val memoryFree: Long,
    val wifiSsid: String
) {
    /**
     * Memory usage as a percentage (0.0 - 1.0).
     * Returns 0 if memoryTotal is 0 to avoid division by zero.
     */
    val memoryUsageRatio: Float
        get() = if (memoryTotal > 0) {
            ((memoryTotal - memoryFree).toFloat() / memoryTotal.toFloat()).coerceIn(0f, 1f)
        } else 0f

    /**
     * Memory used in KB.
     */
    val memoryUsed: Long
        get() = (memoryTotal - memoryFree).coerceAtLeast(0)
}
