package com.spaceboy.ridebuddy.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.DirectionsBoat
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.NavigationKeyUiState
import com.spaceboy.ridebuddy.data.AppSettings

@Composable
fun NavigationSettingsScreen(
    modifier: Modifier = Modifier,
    state: NavigationKeyUiState,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
    settings: AppSettings,
    onVoiceGuidanceChanged: (Boolean) -> Unit,
    onAvoidTollsChanged: (Boolean) -> Unit,
    onAvoidHighwaysChanged: (Boolean) -> Unit,
    onAvoidFerriesChanged: (Boolean) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.maskedKey) {
        if (state.isConfigured) apiKey = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        text = if (state.isConfigured) "API key configured" else "API key required",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = state.maskedKey ?: "Add a key to enable Google turn-by-turn navigation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.restartRequired) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Restart the app before navigating so the SDK can use the changed key.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (state.isConfigured) "Replace API key" else "Google Navigation API key",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                placeholder = { Text("Paste your restricted key") },
                singleLine = true,
                visualTransformation = if (showApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showApiKey) "Hide API key" else "Show API key",
                        )
                    }
                },
                isError = state.errorMessage != null,
                supportingText = if (state.errorMessage != null) {
                    { Text(state.errorMessage) }
                } else {
                    null
                },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    apiKey = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
                }) { Text("Paste") }
                Button(
                    onClick = { onSave(apiKey) },
                    enabled = apiKey.isNotBlank() && !state.isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (state.isConfigured) "Replace key" else "Save key")
                }
            }
            if (state.isConfigured) {
                Row(modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onTest) { Text("Test setup") }
                    TextButton(
                        onClick = {
                            apiKey = ""
                            showApiKey = false
                            onRemove()
                        },
                    ) { Text("Remove key") }
                }
            }
        }

        Text(
            text = "The key is encrypted with Android Keystore and never shown again. Restrict it in Google Cloud to this app's package and signing certificate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Route preferences",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .semantics { heading() },
            )
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    PreferenceSwitch("Voice guidance", "Play spoken instructions", settings.voiceGuidance, Icons.Outlined.RecordVoiceOver, onVoiceGuidanceChanged)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    PreferenceSwitch("Avoid tolls", "Prefer routes without toll roads", settings.avoidTolls, Icons.Outlined.Toll, onAvoidTollsChanged)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    PreferenceSwitch("Avoid highways", "Prefer local roads where possible", settings.avoidHighways, Icons.AutoMirrored.Outlined.AltRoute, onAvoidHighwaysChanged)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    PreferenceSwitch("Avoid ferries", "Do not include ferries in routes", settings.avoidFerries, Icons.Outlined.DirectionsBoat, onAvoidFerriesChanged)
                }
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(
    title: String,
    supporting: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}
