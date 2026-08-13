package com.uspcontroller.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.uspcontroller.app.ui.SetOperationState

/**
 * Card with an input field to update the Wi-Fi passphrase via USP Set message.
 *
 * Features:
 * - Password visibility toggle
 * - Character count (8-63 valid range indicator)
 * - Send button with loading/disabled states
 * - Inline success/error feedback
 */
@Composable
fun WifiConfigPanel(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    state: SetOperationState,
    modifier: Modifier = Modifier
) {
    var passwordVisible by remember { mutableStateOf(false) }

    val isValid = input.length in 8..63
    val isInProgress = state is SetOperationState.InProgress

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Wi-Fi Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("New Passphrase") },
                placeholder = { Text("Enter new Wi-Fi password") },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                supportingText = {
                    val charCount = input.length
                    val color = when {
                        charCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        charCount in 8..63 -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = "$charCount/63 characters (min 8)",
                        color = color
                    )
                },
                singleLine = true,
                enabled = !isInProgress,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (isValid && !isInProgress) onSend() }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator
                when (state) {
                    is SetOperationState.Success -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Success",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                    is SetOperationState.Failed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = state.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2
                            )
                        }
                    }
                    else -> { /* Idle or InProgress — no inline text */ }
                }

                Button(
                    onClick = onSend,
                    enabled = isValid && !isInProgress
                ) {
                    if (isInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Send")
                    }
                }
            }
        }
    }
}
