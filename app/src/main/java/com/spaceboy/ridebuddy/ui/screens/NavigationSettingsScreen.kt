package com.spaceboy.ridebuddy.ui.screens

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import com.spaceboy.ridebuddy.ui.components.SettingsSwitchRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.NavigationKeyUiState
import com.spaceboy.ridebuddy.data.AppSettings

/**
 * Navigation setup: the rider's own Google Navigation SDK key, and the route preferences
 * that apply to every route.
 *
 * The key is the rider's billable credential, so it is entered here, stored encrypted, and
 * only ever shown masked afterwards. Replacing an active key needs an app restart — the SDK
 * accepts one key per process — which the state carries as a flag and this screen surfaces.
 */
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
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val keyOperationInProgress = state.isLoading || state.isSaving

    DisposableEffect(activity) {
        val window = activity?.window
        val wasSecure = (window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) ?: 0) != 0
        if (!wasSecure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(state.maskedKey) {
        if (state.isConfigured) apiKey = ""
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Remove API key?") },
            text = { Text("You’ll need to add a key again to start new routes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    apiKey = ""
                    showApiKey = false
                    onRemove()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } },
        )
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
                        text = when {
                            state.isLoading -> "Checking API key"
                            state.isConfigured -> "API key configured"
                            else -> "API key required"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            state.isLoading -> "Loading…"
                            state.maskedKey != null -> state.maskedKey
                            else -> "Add a key to enable Google turn-by-turn navigation"
                        },
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
                        text = "Restart the app to use the updated key.",
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
                    IconButton(
                        onClick = { showApiKey = !showApiKey },
                        enabled = !keyOperationInProgress,
                    ) {
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
                enabled = !keyOperationInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        apiKey = if (clip != null && clip.itemCount > 0) {
                            clip.getItemAt(0).coerceToText(context).toString().trim()
                        } else {
                            ""
                        }
                    },
                    enabled = !keyOperationInProgress,
                ) { Text("Paste") }
                Button(
                    onClick = { onSave(apiKey) },
                    enabled = apiKey.isNotBlank() && !keyOperationInProgress,
                ) {
                    if (keyOperationInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(ButtonDefaults.IconSize),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (state.isConfigured) "Replace key" else "Save key")
                }
            }
            if (state.isConfigured) {
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onTest, enabled = !keyOperationInProgress) { Text("Test setup") }
                    TextButton(
                        onClick = { confirmRemoval = true },
                        enabled = !keyOperationInProgress,
                    ) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Text(
            text = "Stored securely on this device. In Google Cloud, restrict the key to RideBuddy’s package and signing certificate.",
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
                    SettingsSwitchRow("Voice guidance", "Play spoken instructions", settings.voiceGuidance, icon = Icons.Outlined.RecordVoiceOver, onCheckedChange = onVoiceGuidanceChanged)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    SettingsSwitchRow("Avoid tolls", "Prefer routes without toll roads", settings.avoidTolls, icon = Icons.Outlined.Toll, onCheckedChange = onAvoidTollsChanged)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    SettingsSwitchRow("Avoid highways", "Prefer local roads where possible", settings.avoidHighways, icon = Icons.AutoMirrored.Outlined.AltRoute, onCheckedChange = onAvoidHighwaysChanged)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    SettingsSwitchRow("Avoid ferries", "Prefer routes without ferries", settings.avoidFerries, icon = Icons.Outlined.DirectionsBoat, onCheckedChange = onAvoidFerriesChanged)
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
