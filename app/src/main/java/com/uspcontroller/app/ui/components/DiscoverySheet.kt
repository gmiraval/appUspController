package com.uspcontroller.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.uspcontroller.app.data.discovery.AgentInfo

/**
 * Discovery panel showing found USP agents and a manual connection option.
 *
 * Features:
 * - List of discovered agents (tap to connect)
 * - Scanning indicator with animation
 * - Manual URL entry with connect button
 * - Empty state message
 */
@Composable
fun DiscoverySheet(
    agents: List<AgentInfo>,
    isDiscovering: Boolean,
    onSelectAgent: (AgentInfo) -> Unit,
    onManualConnect: (url: String, eid: String) -> Unit,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var manualUrl by remember { mutableStateOf("") }
    var manualEid by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with scan button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "USP Agents",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isDiscovering) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Scanning...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    OutlinedButton(onClick = onStartScan) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Scan", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Discovered agents list
            if (agents.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.height((agents.size * 56).coerceAtMost(224).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(agents, key = { it.endpointId }) { agent ->
                        AgentListItem(agent = agent, onClick = { onSelectAgent(agent) })
                    }
                }
            } else if (!isDiscovering) {
                Text(
                    text = "No agents found. Tap Scan or use manual entry below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Manual connection section
            Text(
                text = "Manual Connection",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text("WebSocket URL") },
                placeholder = { Text("ws://192.168.1.100:8080/usp") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = manualEid,
                onValueChange = { manualEid = it },
                label = { Text("Agent Endpoint ID (optional)") },
                placeholder = { Text("os::agent-123") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (manualUrl.isNotBlank()) {
                            onManualConnect(manualUrl, manualEid.ifBlank { "manual-agent" })
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    onManualConnect(manualUrl, manualEid.ifBlank { "manual-agent" })
                },
                enabled = manualUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect")
            }
        }
    }
}

@Composable
private fun AgentListItem(
    agent: AgentInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.Router,
            contentDescription = "Agent",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agent.serviceName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${agent.endpointId} - ${agent.host}:${agent.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
