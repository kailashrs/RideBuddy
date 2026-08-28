package com.spaceboy.ridebuddy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsSliderRow(
    title: String,
    supportingText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(supportingText) },
            leadingContent = icon?.let {
                {
                    Box(modifier = Modifier.padding(top = 4.dp)) {
                        Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue) },
            enabled = enabled,
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                .semantics { contentDescription = title },
        )
    }
}

/** These choices are exclusive, so Material 3 asks for a segmented button rather than chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsChoiceRow(
    title: String,
    choices: List<T>,
    selectedChoice: T,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    choiceLabel: (T) -> String = { it.toString() },
    onSelected: (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = icon?.let {
                {
                    Box(modifier = Modifier.padding(top = 4.dp)) {
                        Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
        ) {
            choices.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = choice == selectedChoice,
                    onClick = { onSelected(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                    enabled = enabled,
                    label = { Text(choiceLabel(choice)) },
                )
            }
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        leadingContent = icon?.let {
            {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp)
                .semantics { heading() },
        )
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = { content() })
        }
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    /** Replaces the navigation chevron for rows whose action is a button rather than the row. */
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) {
        modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        modifier
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        leadingContent = {
            Box(modifier = Modifier.padding(top = 4.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            when {
                trailingContent != null -> trailingContent()
                onClick != null ->
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = clickModifier,
    )
}
