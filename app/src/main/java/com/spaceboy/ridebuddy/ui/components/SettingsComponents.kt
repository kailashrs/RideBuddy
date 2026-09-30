package com.spaceboy.ridebuddy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Shared building blocks for the settings screens, so every row looks and behaves the same
// and each screen only has to say what the setting is.

/**
 * A setting with a continuous value.
 *
 * The slider position is held locally and re-seeded whenever the incoming value changes, so
 * dragging stays smooth without a round trip through the settings repository for every
 * intermediate position.
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
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(valueLabel(sliderValue)) },
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
                .semantics { contentDescription = title; stateDescription = valueLabel(sliderValue) },
        )
    }
}

/**
 * Two or three exclusive choices with short labels, as a segmented button.
 *
 * Material 3 sizes a segmented button for labels a few characters long and gives each segment
 * an equal share of the row, so a label that does not fit wraps inside its own segment rather
 * than the row adapting. Anything longer or more numerous belongs in [SettingsPickerRow].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsChoiceRow(
    title: String,
    choices: List<T>,
    selectedChoice: T,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    enabled: Boolean = true,
    choiceLabel: (T) -> String = { it.toString() },
    onSelected: (T) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp || fontScale > 1.1f) {
            SettingsPickerRow(icon, title, choices, selectedChoice, enabled = enabled,
                choiceLabel = choiceLabel, onSelected = onSelected)
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(title) },
                    leadingContent = icon.let {
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
    }
}

/**
 * An exclusive choice with more options, or longer labels, than a segmented button can carry.
 *
 * The row shows the current choice the way every other settings row shows its state, and opens
 * a single-choice dialog. That is the Material 3 form for this — a segmented button holding
 * five phrases like "Keep everything" splits them mid-word, one character per line, because
 * each segment is a fixed fraction of the row whatever the label needs.
 *
 * Each option is one accessibility node, not a radio button next to some text: the row carries
 * the selection and the radio is decorative, which is the same reason [SettingsSwitchRow] gives
 * its switch no callback of its own.
 */
@Composable
internal fun <T> SettingsPickerRow(
    icon: ImageVector,
    title: String,
    choices: List<T>,
    selectedChoice: T,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    choiceLabel: (T) -> String = { it.toString() },
    onSelected: (T) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        icon = icon,
        title = title,
        supportingText = choiceLabel(selectedChoice),
        modifier = modifier,
        enabled = enabled,
        onClick = { picking = true },
        // No chevron: that points at another screen, and this opens a dialog. The current
        // choice sitting under the title is what says the row does something.
        trailingContent = {},
    )
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(title) },
            text = {
                // The dialog caps its own height but does not scroll what it is given.
                Column(
                    Modifier
                        .selectableGroup()
                        .verticalScroll(rememberScrollState()),
                ) {
                    choices.forEach { choice ->
                        val isSelected = choice == selectedChoice
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // The Material 3 minimum touch target, which the text alone
                                // would not reach on a single line.
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onSelected(choice)
                                        picking = false
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Null, so the row owns the click and a screen reader announces one
                            // control rather than a button beside a label.
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(choiceLabel(choice), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            // Choosing applies and closes, as Android's own settings do, so the only button
            // left is the way out without choosing.
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A setting that is on or off.
 *
 * The switch itself takes no callback — the whole row is `toggleable` instead — so the tap
 * target is the full row and accessibility services announce one control, not two.
 */
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
    val largeText = LocalDensity.current.fontScale > 1.3f
    val control: @Composable () -> Unit = {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(supportingText)
                if (largeText) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { control() }
            }
        },
        leadingContent = icon?.takeUnless { largeText }?.let {
            {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = if (largeText) null else control,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

/** A titled group of settings rows. The title is marked as a heading for screen readers. */
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

/** A row that performs an action or opens something, rather than holding a value. */
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
    val largeText = LocalDensity.current.fontScale > 1.3f
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
        leadingContent = if (largeText) null else { {
            Box(modifier = Modifier.padding(top = 4.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        } },
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
