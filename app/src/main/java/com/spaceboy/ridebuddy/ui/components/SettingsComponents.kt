package com.spaceboy.ridebuddy.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// Shared building blocks for settings-style lists: full-width Material list items under
// subheaders, so every row looks and behaves the same and each screen only says what it is.

/** A list subheader. The caller supplies horizontal padding to match its content. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A row that shows a value or opens something. Clickable only when [onClick] is set. */
@Composable
internal fun SettingsRow(
    title: String,
    supportingText: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = trailingContent,
        // ListItem has no enabled state of its own; this is Material's 38% disabled content.
        colors = if (enabled) ListItemDefaults.colors() else ListItemDefaults.colors(
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            supportingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            leadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        modifier = if (onClick != null) {
            modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        } else modifier,
    )
}

/**
 * A setting that is on or off. The whole row is `toggleable` and the switch takes no callback
 * of its own, so the tap target is the full row and a screen reader announces one control.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    supportingText: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/**
 * A setting with a continuous value. The slider position is held locally and only written back
 * when the drag ends, so dragging does not round-trip through the settings store.
 */
@Composable
internal fun SettingsSliderRow(
    title: String,
    valueLabel: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onValueChange: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(valueLabel(sliderValue)) },
            // Without an icon the slot is still reserved, so a threshold nests under its switch.
            leadingContent = { if (icon != null) Icon(icon, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue) },
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp)
                .semantics { contentDescription = title; stateDescription = valueLabel(sliderValue) },
        )
    }
}

/**
 * An exclusive choice: the row shows the current value and opens a single-choice dialog, as
 * Android's own settings do. Choosing applies and closes, so the only button is Cancel.
 */
@Composable
internal fun <T> SettingsPickerRow(
    title: String,
    choices: List<T>,
    selectedChoice: T,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    choiceLabel: (T) -> String = { it.toString() },
    onSelected: (T) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    SettingsRow(title, choiceLabel(selectedChoice), modifier, icon, onClick = { picking = true })
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        val selected = choice == selectedChoice
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected, role = Role.RadioButton, onClick = {
                                    onSelected(choice)
                                    picking = false
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(choiceLabel(choice), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}
