package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.spaceboy.ridebuddy.data.db.Destination
import com.spaceboy.ridebuddy.data.recents
import com.spaceboy.ridebuddy.data.saved

/**
 * Where a route can start from: the most frequent recent places, then the saved ones, then a way
 * into Google Maps — a share from Maps is how a new place arrives. Full-width rows, since this is
 * tapped on a handlebar mount.
 */
@Composable
internal fun DestinationList(
    destinations: List<Destination>,
    onNavigateTo: (Destination) -> Unit,
    onOpenGoogleMaps: () -> Unit,
    onRename: (id: Long, name: String) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    val recents = remember(destinations) { destinations.recents() }
    val saved = remember(destinations) { destinations.saved() }
    // Ids rather than rows, so a dialog survives rotation and follows the row if it changes.
    var naming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    destinations.firstOrNull { it.id == naming }?.let { place ->
        NameDestinationDialog(
            title = if (place.isSaved) "Rename place" else "Save place",
            initialName = place.savedName ?: place.placeName,
            onDismiss = { naming = null },
            onConfirm = { name -> naming = null; onRename(place.id, name) },
        )
    }
    destinations.firstOrNull { it.id == deleting }?.let { place ->
        ConfirmDialog(
            title = "Delete ${place.savedName}?",
            text = "It is removed from your saved places. Trips there are forgotten too.",
            confirm = "Delete",
            onDismiss = { deleting = null },
            onConfirm = { onDelete(place.id) },
        )
    }

    Column {
        if (recents.isEmpty() && saved.isEmpty()) {
            ListItem(
                headlineContent = { Text("No places yet") },
                supportingContent = { Text("Places you ride to appear here, most frequent first") },
                leadingContent = { Icon(Icons.Outlined.Place, contentDescription = null) },
                colors = Transparent,
            )
        }
        recents.forEach { place ->
            DestinationRow(
                icon = Icons.Outlined.History,
                headline = place.placeName,
                supporting = tripsLabel(place),
                onClick = { onNavigateTo(place) },
                actions = listOf(
                    MenuAction("Save", Icons.Outlined.BookmarkAdd) { naming = place.id },
                    MenuAction("Remove", Icons.Outlined.DeleteOutline) { onDelete(place.id) },
                ),
            )
        }
        if (recents.isNotEmpty() && saved.isNotEmpty()) HorizontalDivider()
        saved.forEach { place ->
            DestinationRow(
                icon = Icons.Outlined.Bookmark,
                headline = place.savedName.orEmpty(),
                supporting = place.placeName,
                onClick = { onNavigateTo(place) },
                actions = listOf(
                    MenuAction("Rename", Icons.Outlined.Edit) { naming = place.id },
                    MenuAction("Delete", Icons.Outlined.DeleteOutline) { deleting = place.id },
                ),
            )
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Find a place in Google Maps") },
            supportingContent = { Text("Then share it to RideBuddy") },
            leadingContent = { Icon(Icons.Outlined.Map, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            colors = Transparent,
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenGoogleMaps),
        )
    }
}

private class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun DestinationRow(
    icon: ImageVector,
    headline: String,
    supporting: String,
    onClick: () -> Unit,
    actions: List<MenuAction>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options for $headline")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            leadingIcon = { Icon(action.icon, contentDescription = null) },
                            onClick = { menuOpen = false; action.onClick() },
                        )
                    }
                }
            }
        },
        colors = Transparent,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Navigate", onClick = onClick),
    )
}

/**
 * The route preview's heading: the saved name when there is one, with Save beside it as the
 * preview's one secondary action. Filled once saved; tapping it then renames.
 */
@Composable
internal fun RoutePreviewTitle(title: String, saved: Boolean, onSave: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onSave != null) {
            IconButton(onClick = onSave) {
                Icon(
                    if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkAdd,
                    contentDescription = if (saved) "Rename saved place" else "Save place",
                    tint = if (saved) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
    }
}

/** "14 trips · 2 days ago". */
private fun tripsLabel(place: Destination): String {
    val trips = if (place.tripCount == 1) "1 trip" else "${place.tripCount} trips"
    val last = place.lastTripAtMillis ?: return trips
    val ago = DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
    return "$trips · $ago"
}

/** Rows sit on the card's own surface. */
private val Transparent @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * Asks for a place's name. The suggested name arrives selected, so typing replaces it and Done
 * keeps it.
 */
@Composable
internal fun NameDestinationDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = name.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.text.length <= MaxNameLength) name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name.text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.text) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MaxNameLength = 40
