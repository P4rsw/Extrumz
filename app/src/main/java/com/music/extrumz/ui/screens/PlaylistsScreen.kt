package com.extrumz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.extrumz.app.R
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.db.entity.Playlist
import com.extrumz.app.ui.util.ProvideLocaleDirection
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.style.TextAlign

/**
 * Every playlist the user has made, with the two things you can do to one.
 *
 * No cover anywhere on this screen. A playlist is a list of songs the listener
 * picked, not an image, and giving the row a picture would mean either an
 * arbitrary screenshot of whatever is in it or an upload step nobody asked
 * for — the same reason the channels screen shows a letter and not a photo.
 *
 * "New playlist" sits above the list rather than under it. A button at the
 * foot of a scrolling list is a button you have to know is there.
 */
@Composable
fun PlaylistsScreen(
    contentPadding: PaddingValues,
    listState: LazyListState,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideLocaleDirection {
        val dao = TelegramDb.playlistDao
        val playlists by dao.observeAll().collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()

        // Which dialog is open, if any. Held as one value rather than three
        // booleans so two can never be true at once.
        var naming by remember { mutableStateOf<Playlist?>(null) }
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<Playlist?>(null) }

        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.playlists),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { creating = true },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.playlists_new))
                }
            }

            if (playlists.isEmpty()) {
                // An empty state carries an action: saying there are no
                // playlists and stopping leaves the user to find the button
                // they were just told does not exist.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.playlists_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.playlists_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // The action the comment above demands. Told there are no
                    // playlists, the next thing the user wants is to make one,
                    // and the header button is not where they are looking.
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { creating = true }, shape = RoundedCornerShape(999.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.playlists_new))
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = contentPadding.calculateBottomPadding() + 96.dp,
                    ),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onOpenPlaylist(playlist.id) },
                            onRename = { naming = playlist },
                            onDelete = { deleting = playlist },
                        )
                    }
                }
            }
        }

        val openNaming = naming
        if (creating || openNaming != null) {
            NameDialog(
                initial = openNaming?.name.orEmpty(),
                isRenaming = openNaming != null,
                onDismiss = { creating = false; naming = null },
                onConfirm = { entered ->
                    val name = entered.trim()
                    if (name.isNotEmpty()) {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            dao.upsert(
                                openNaming?.copy(name = name, updatedAt = now)
                                    ?: Playlist(
                                        id = UUID.randomUUID().toString(),
                                        name = name,
                                        description = null,
                                        coverUrl = null,
                                        telegramChannelId = null,
                                        telegramInviteLink = null,
                                        createdAt = now,
                                        updatedAt = now,
                                    ),
                            )
                        }
                    }
                    creating = false
                    naming = null
                },
            )
        }

        val doomed = deleting
        if (doomed != null) {
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.playlists_delete_title)) },
                text = { Text(stringResource(R.string.playlists_delete_confirm, doomed.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { dao.delete(doomed.id) }
                        deleting = null
                    }) { Text(stringResource(R.string.playlists_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) {
                        Text(stringResource(R.string.playlists_cancel))
                    }
                },
            )
        }
    }
}

/**
 * One playlist: its name, how many songs are in it, and the two things you can
 * do to it.
 *
 * The menu is a child of the row rather than of a shared parent, so only the
 * row whose button was pressed can be the one showing a menu.
 */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // The DAO's own membership stream, counted here. A row only exists while
    // its playlist does, so this is one flow per row on screen and nothing
    // for the ones scrolled past.
    val tracks by TelegramDb.playlistDao.observeTracks(playlist.id)
        .collectAsState(initial = emptyList())

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        // The letter, as on the channels screen. A playlist has no artwork and
        // inventing one would be a lie about what is inside it.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                playlist.name.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.playlists_count, tracks.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlists_rename)) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlists_delete)) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/**
 * One dialog for both naming a new playlist and renaming an existing one — the
 * only difference is what it starts with and what it is called.
 */
@Composable
private fun NameDialog(
    initial: String,
    isRenaming: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Keyed on the initial value: the dialog is reused for every rename, so an
    // unkeyed remember would keep the first name it was ever opened with.
    var text by remember(initial) { mutableStateOf(initial) }
    val canConfirm = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // isRenaming, not initial.isEmpty(): a playlist whose name is
            // blank would otherwise be renamed through a dialog titled "New
            // playlist".
            Text(stringResource(if (isRenaming) R.string.playlists_rename else R.string.playlists_new))
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlists_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = canConfirm) {
                Text(stringResource(if (isRenaming) R.string.playlists_rename else R.string.playlists_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlists_cancel)) }
        },
    )
}
