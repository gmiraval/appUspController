package com.uspcontroller.app.domain.usecase

import com.uspcontroller.app.data.repository.UspRepository
import com.uspcontroller.app.domain.model.DeviceMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Use case that periodically polls device metrics from the USP Agent.
 *
 * Emits a [Flow] of [Result]<[DeviceMetrics]> at a configurable interval.
 * Runs on [Dispatchers.IO] to keep the main thread free.
 *
 * Polled TR-181 parameters:
 * - Device.DeviceInfo.ProcessStatus.CPUUsage
 * - Device.DeviceInfo.MemoryStatus.Total
 * - Device.DeviceInfo.MemoryStatus.Free
 * - Device.WiFi.SSID.1.SSID
 */
class PollDeviceMetricsUseCase @Inject constructor(
    private val repository: UspRepository
) {

    companion object {
        private const val DEFAULT_INTERVAL_MS = 5000L

        private const val PATH_CPU_USAGE = "Device.DeviceInfo.ProcessStatus.CPUUsage"
        private const val PATH_MEMORY_TOTAL = "Device.DeviceInfo.MemoryStatus.Total"
        private const val PATH_MEMORY_FREE = "Device.DeviceInfo.MemoryStatus.Free"
        private const val PATH_WIFI_SSID = "Device.WiFi.SSID.1.SSID"

        val MONITORED_PATHS = listOf(
            PATH_CPU_USAGE,
            PATH_MEMORY_TOTAL,
            PATH_MEMORY_FREE,
            PATH_WIFI_SSID
        )
    }

    /**
     * Starts a polling loop that fetches device metrics at the given interval.
     *
     * The flow emits indefinitely until cancelled (e.g., when the ViewModel's scope ends
     * or the connection drops).
     *
     * @param agentEid The Endpoint ID of the connected USP Agent.
     * @param intervalMs Polling interval in milliseconds. Defaults to 5000ms.
     * @return An infinite [Flow] of [Result]<[DeviceMetrics]>.
     */
    fun execute(agentEid: String, intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<Result<DeviceMetrics>> = flow {
        while (true) {
            val result = repository.getParameters(agentEid, MONITORED_PATHS)

            emit(result.map { params -> mapToDeviceMetrics(params) })

            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Maps the raw parameter key-value pairs to a [DeviceMetrics] domain object.
     *
     * Handles missing or unparseable values gracefully with safe defaults.
     */
    private fun mapToDeviceMetrics(params: Map<String, String>): DeviceMetrics {
        return DeviceMetrics(
            cpuUsage = params[PATH_CPU_USAGE]?.toIntOrNull() ?: 0,
            memoryTotal = params[PATH_MEMORY_TOTAL]?.toLongOrNull() ?: 0L,
            memoryFree = params[PATH_MEMORY_FREE]?.toLongOrNull() ?: 0L,
            wifiSsid = params[PATH_WIFI_SSID] ?: "Unknown"
        )
    }
}
