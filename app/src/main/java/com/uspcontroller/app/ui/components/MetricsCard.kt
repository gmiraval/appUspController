package com.uspcontroller.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uspcontroller.app.ui.MetricsUiState

/**
 * Dashboard card displaying real-time device metrics from the USP Agent.
 *
 * Shows: CPU usage (with progress bar), memory total/free/used, and current Wi-Fi SSID.
 * Displays a loading indicator when data is being fetched.
 */
@Composable
fun MetricsCard(
    metrics: MetricsUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Device Metrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (metrics.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            if (!metrics.hasData && metrics.isLoading) {
                Text(
                    text = "Fetching metrics...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (metrics.hasData) {
                // CPU Usage
                MetricRow(
                    icon = { Icon(Icons.Filled.Speed, contentDescription = "CPU", modifier = Modifier.size(24.dp)) },
                    label = "CPU Usage",
                    value = "${metrics.cpuUsage ?: 0}%"
                )
                LinearProgressIndicator(
                    progress = { (metrics.cpuUsage ?: 0) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Memory
                MetricRow(
                    icon = { Icon(Icons.Filled.Memory, contentDescription = "Memory", modifier = Modifier.size(24.dp)) },
                    label = "Memory",
                    value = "${formatKb(metrics.memoryFree)} free / ${formatKb(metrics.memoryTotal)} total"
                )
                LinearProgressIndicator(
                    progress = { metrics.memoryUsageRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Wi-Fi SSID
                MetricRow(
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = "Wi-Fi", modifier = Modifier.size(24.dp)) },
                    label = "Wi-Fi SSID",
                    value = metrics.wifiSsid ?: "—"
                )

                // Last updated
                if (metrics.lastUpdated > 0) {
                    Text(
                        text = "Last updated: ${formatTimestamp(metrics.lastUpdated)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatKb(kb: Long?): String {
    if (kb == null) return "—"
    return if (kb >= 1024) {
        "${kb / 1024} MB"
    } else {
        "$kb KB"
    }
}

private fun formatTimestamp(millis: Long): String {
    val seconds = (System.currentTimeMillis() - millis) / 1000
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        else -> "${seconds / 60}m ago"
    }
}
