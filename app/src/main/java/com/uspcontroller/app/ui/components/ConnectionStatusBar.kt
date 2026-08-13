package com.uspcontroller.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uspcontroller.app.data.transport.ConnectionState

private val ConnectedColor = Color(0xFF2E7D32)
private val ConnectingColor = Color(0xFFF9A825)
private val DisconnectedColor = Color(0xFFC62828)
private val ReconnectingColor = Color(0xFFEF6C00)

/**
 * A color-coded status bar showing the WebSocket MTP connection state.
 *
 * Colors: Green (Connected), Yellow (Connecting), Orange (Reconnecting), Red (Disconnected/Error).
 */
@Composable
fun ConnectionStatusBar(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (state) {
            is ConnectionState.Connected -> ConnectedColor
            is ConnectionState.Connecting -> ConnectingColor
            is ConnectionState.Reconnecting -> ReconnectingColor
            is ConnectionState.Disconnected -> DisconnectedColor
            is ConnectionState.Error -> DisconnectedColor
        },
        label = "statusBarColor"
    )

    val icon: ImageVector = when (state) {
        is ConnectionState.Connected -> Icons.Filled.Link
        is ConnectionState.Connecting -> Icons.Filled.Sync
        is ConnectionState.Reconnecting -> Icons.Filled.Sync
        is ConnectionState.Disconnected -> Icons.Filled.LinkOff
        is ConnectionState.Error -> Icons.Filled.Warning
    }

    val text: String = when (state) {
        is ConnectionState.Connected -> "Connected: ${state.agentEid}"
        is ConnectionState.Connecting -> "Connecting..."
        is ConnectionState.Reconnecting -> "Reconnecting (attempt ${state.attempt})..."
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Error -> "Error: ${state.reason}"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Connection status",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
