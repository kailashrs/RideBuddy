package com.spaceboy.ridebuddy.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.spaceboy.ridebuddy.core.navigation.NavigationKeyUiState
import com.spaceboy.ridebuddy.ui.components.SectionHeader

/**
 * Navigation setup: the rider's own Google Navigation SDK key. Route preferences live in
 * Settings with the other riding options.
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
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        ListItem(
            headlineContent = {
                Text(when {
                    state.isLoading -> "Checking key…"
                    state.isConfigured -> "Key saved"
                    else -> "No key yet"
                })
            },
            supportingContent = {
                Text(state.maskedKey ?: "Turn-by-turn directions need your own Google Navigation SDK key")
            },
            leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
        )
        if (state.restartRequired) {
            ListItem(
                headlineContent = { Text("Restart RideBuddy to use the new key") },
                leadingContent = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
            )
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(if (state.isConfigured) "Replace key" else "Add key")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }, enabled = !keyOperationInProgress) {
                        Icon(
                            if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showApiKey) "Hide API key" else "Show API key",
                        )
                    }
                },
                isError = state.errorMessage != null,
                supportingText = {
                    Text(state.errorMessage ?: "Restrict the key to RideBuddy's package and signing certificate. It is stored encrypted on this phone.")
                },
                enabled = !keyOperationInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isConfigured) {
                    TextButton(
                        onClick = { confirmRemoval = true },
                        enabled = !keyOperationInProgress,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Remove") }
                    OutlinedButton(onClick = onTest, enabled = !keyOperationInProgress) { Text("Test") }
                }
                Button(onClick = { onSave(apiKey) }, enabled = apiKey.isNotBlank() && !keyOperationInProgress) {
                    if (keyOperationInProgress) {
                        CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text("Save")
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
