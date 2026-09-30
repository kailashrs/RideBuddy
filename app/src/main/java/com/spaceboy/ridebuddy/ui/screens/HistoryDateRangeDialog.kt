package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePickerDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.data.HistoryFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryDateRangeDialog(
    initialRange: HistoryFilter.Dates?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onApply: (HistoryFilter.Dates) -> Unit,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val width = with(density) { windowSize.width.toDp() }
    val height = with(density) { windowSize.height.toDp() }
    val compactInput = (width > 0.dp && width < 360.dp) ||
        (height > 0.dp && height < 480.dp) || density.fontScale >= 1.5f
    val dialogWidth = if (width > 0.dp) (width - 32.dp).coerceIn(0.dp, 360.dp) else 360.dp
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale)
    }
    val selectableDates = remember(today) { object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) =
            Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= today
        override fun isSelectableYear(year: Int) = year <= today.year
    } }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialRange?.start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = initialRange?.endInclusive?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialDisplayedMonthMillis = (initialRange?.start ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialDisplayMode = if (compactInput)
            DisplayMode.Input else DisplayMode.Picker,
        selectableDates = selectableDates,
    )
    // The picker saves its display mode, so rotation must also adapt an already-open picker.
    LaunchedEffect(compactInput) {
        if (compactInput) state.displayMode = DisplayMode.Input
    }
    val picker: @Composable () -> Unit = {
        DateRangePicker(
            state = state,
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            title = {
                Text(stringResource(R.string.history_choose_dates), Modifier.padding(24.dp),
                    style = MaterialTheme.typography.titleLarge)
            },
            headline = if (compactInput) null else {
                {
                    // The default display-sized headline gives the second date only the
                    // width left by the first. Equal columns keep both dates readable.
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(R.string.history_start_date to state.selectedStartDateMillis,
                            R.string.history_end_date to state.selectedEndDateMillis).forEach { (label, millis) ->
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                                Text(millis?.let {
                                    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(dateFormat)
                                } ?: "—", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            showModeToggle = !compactInput,
        )
    }
    val cancel: @Composable () -> Unit = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) }
    }
    val apply: @Composable () -> Unit = {
        TextButton(
            enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
            onClick = {
                val start = state.selectedStartDateMillis ?: return@TextButton
                val end = state.selectedEndDateMillis ?: return@TextButton
                onApply(HistoryFilter.Dates(
                    Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                    Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate(),
                ))
            },
        ) { Text(stringResource(R.string.history_apply)) }
    }
    if (!compactInput) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = apply,
            dismissButton = cancel,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) { picker() }
    } else {
        // The standard dialog requires 360 dp. Keep text input usable below that width
        // and in short windows without squeezing the calendar or its touch targets.
        BasicAlertDialog(onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.width(dialogWidth).heightIn(max = 560.dp),
                shape = DatePickerDefaults.shape,
                color = DatePickerDefaults.colors().containerColor,
                tonalElevation = DatePickerDefaults.TonalElevation) {
                Column {
                    Box(Modifier.weight(1f, fill = false)) { picker() }
                    FlowRow(Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        cancel()
                        apply()
                    }
                }
            }
        }
    }
}
