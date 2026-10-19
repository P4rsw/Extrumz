package com.extrumz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlaylistAdd
import com.extrumz.app.ui.components.ExtrumzInputDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.model.Song
import com.extrumz.app.ui.util.ProvideLocaleDirection
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Pick a playlist to put this song in.
 *
 * The song has to become a `Track` first, because a playlist stores
 * `trackFileUniqueId` and a `Song` does not carry one under that name. Two
 * routes, in order of confidence:
 *
 * 1. `setVideoId` — the shared mapper puts the row's `fileUniqueId` there for
 *    exactly this kind of question, and it is authoritative when present.
 * 2. The `videoId` is `src:<configId>::tg_<chatId>_<messageId>` for anything
 *    filed from Telegram, so the pair can be read back out of it and looked
 *    up. A song from anywhere else — a search result, a downloaded file —
 *    has neither and simply has nowhere to go.
 *
 * Membership is checked before adding rather than left to the insert.
 * `addTrack` writes through `OnConflictStrategy.REPLACE`, so adding a track
 * that is already there would not fail — it would move the song to the end of
 * the list, silently, and leave a gap behind.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    song: Song,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dao = TelegramDb.playlistDao
    val scope = rememberCoroutineScope()

    val playlists by dao.observeAll().collectAsState(initial = emptyList())
    var track by remember(song.videoId) { mutableStateOf<Track?>(null) }
    var resolving by remember(song.videoId) { mutableStateOf(true) }
    // The playlist's name, not the finished sentence: the sentence needs a
    // string resource and this is set from inside a coroutine.
    var addedToName by remember(song.videoId) { mutableStateOf<String?>(null) }
    // Naming happens here rather than on the playlists page. Sending the user
    // there to make one, then back here to add the song, is two trips for one
    // intention -- and the button did nothing at all before, because the sheet
    // was left owning a song the page it opened knew nothing about.
    var naming by remember(song.videoId) { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(song.videoId) {
        track = resolveTrack(song)
        resolving = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ProvideLocaleDirection {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    text = stringResource(R.string.playlists_add_to),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = addedToName?.let { stringResource(R.string.playlists_added_to, it) }
                        ?: song.title.ifBlank { song.artist },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }

            val row = track
            when {
                resolving -> Spacer(Modifier.height(24.dp))

                row == null -> Text(
                    text = stringResource(R.string.playlists_add_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )

                playlists.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.playlists_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { naming = true }) {
                        Text(stringResource(R.string.playlists_new))
                    }
                }

                else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        // The DAO's own membership stream, not a one-shot read.
                        // A one-shot was the reason the tick only appeared after
                        // closing and reopening the menu: the sheet was still
                        // showing the answer it fetched before the write.
                        val members by dao.observeTracks(playlist.id)
                            .collectAsState(initial = emptyList())
                        val inside = members.any { it.fileUniqueId == row.fileUniqueId }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                // The tick sat hard against the right edge and
                                // the name against the left, with nothing
                                // between them and the sheet's own edge.
                                .padding(horizontal = 20.dp)
                                .clip(RoundedCornerShape(12.dp))
                                // Tapping a playlist that already holds this
                                // song takes it out again. Leaving the row
                                // inert was worse than either: the menu listed
                                // the playlist, showed it held the song, and
                                // offered no way to act on that.
                                .clickable {
                                    scope.launch {
                                        if (inside) {
                                            dao.removeTrack(playlist.id, row.fileUniqueId)
                                            addedToName = null
                                        } else {
                                            dao.addTrack(
                                                playlist.id,
                                                row.fileUniqueId,
                                                System.currentTimeMillis(),
                                            )
                                            addedToName = playlist.name
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
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
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (inside) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (inside) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.playlists_already),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                TextButton(onClick = { naming = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.playlists_new))
                }
            }
        }

        if (naming) {
            NamePlaylistDialog(
                onDismiss = { naming = false },
                onConfirm = { name ->
                    naming = false
                    val row0 = track ?: return@NamePlaylistDialog
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val playlist = com.extrumz.app.data.db.entity.Playlist(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            description = null,
                            coverUrl = null,
                            telegramChannelId = null,
                            telegramInviteLink = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                        dao.upsert(playlist)
                        dao.addTrack(playlist.id, row0.fileUniqueId, now)
                        addedToName = name
                    }
                },
            )
        }
    }
}

/** Names a playlist that is about to be created with the song already in it. */
@Composable
private fun NamePlaylistDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    ExtrumzInputDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.playlists_new),
        placeholder = stringResource(R.string.playlists_name_hint),
        confirmText = stringResource(R.string.playlists_create),
        dismissText = stringResource(R.string.playlists_cancel),
        icon = Icons.Rounded.PlaylistAdd,
        onConfirm = onConfirm,
    )
}

/**
 * The playlist-membership row behind a song, or null when there is not one.
 *
 * Null is a real answer, not a failure: a search result or a local file has
 * no row in the tracks table and therefore cannot be in a playlist built from
 * one.
 */
private suspend fun resolveTrack(song: Song): Track? {
    song.setVideoId?.let { id ->
        TelegramDb.trackDao.getById(id)?.let { return it }
    }
    val tail = song.videoId.substringAfterLast("::", missingDelimiterValue = "")
        .ifEmpty { song.videoId }
    if (!tail.startsWith("tg_")) return null
    val parts = tail.removePrefix("tg_").split("_")
    val chatId = parts.getOrNull(0)?.toLongOrNull() ?: return null
    val messageId = parts.getOrNull(1)?.toLongOrNull() ?: return null
    return TelegramDb.trackDao.getByChatAndMessage(chatId, messageId)
}
