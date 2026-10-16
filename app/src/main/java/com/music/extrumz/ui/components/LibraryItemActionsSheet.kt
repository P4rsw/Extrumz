package com.extrumz.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.extrumz.app.R
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.toSong
import com.extrumz.app.data.model.ShelfItem
import com.extrumz.app.ui.util.ProvideLocaleDirection

/**
 * The menu behind a Library card, chosen by what the card actually is.
 *
 * Everything used to go through one browse sheet, which is right for a
 * release page — its menu queues tracks the page has — and wrong for the two
 * kinds of card this app owns. A channel is not a release: there is no page
 * or queue behind it and the useful verbs are "filter by it" and "play what
 * is in it". A playlist is not a release either: it is the user's own object
 * and the verbs are the ones the playlists page already offers.
 *
 * Tools (`tg:channels`, `playlists:manage`) never reach here at all; the
 * caller filters them out. Nothing sits behind either.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemActionsSheet(
    item: ShelfItem,
    isPinned: Boolean,
    channelIsSelected: Boolean,
    onDismiss: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFilterChannel: (Long?) -> Unit,
    onRescan: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onTogglePin: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dao = TelegramDb.playlistDao
    val id = item.browseId.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ProvideLocaleDirection {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                // Every menu opens with what it is about, so a mis-tap is
                // visible before anything is acted on.
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            val chatId = id.removePrefix("tg:chat:").takeIf { id.startsWith("tg:chat:") }
                ?.toLongOrNull()
            val playlistId = id.removePrefix("playlist:")
                .takeIf { id.startsWith("playlist:") }

            val songs = when {
                chatId != null -> TelegramDb.trackDao.observeByChat(chatId)
                playlistId != null -> dao.observeTracks(playlistId)
                else -> null
            }?.collectAsState(initial = emptyList())?.value.orEmpty()
            val playable = songs.isNotEmpty()

            when {
                // ── A channel ────────────────────────────────────────────────
                chatId != null -> {
                    ActionRow(
                        icon = Icons.Rounded.FilterAlt,
                        // For a channel the filter IS the main verb, and after
                        // a channel is filtering the list the only thing left
                        // to offer is to stop.
                        label = stringResource(
                            if (channelIsSelected) R.string.chan_clear_filter
                            else R.string.chan_filter_by,
                        ),
                        onClick = { onFilterChannel(if (channelIsSelected) null else chatId) },
                    )
                    ActionRow(
                        icon = Icons.Rounded.PlayArrow,
                        label = stringResource(R.string.chan_play_all),
                        enabled = playable,
                        onClick = { onPlay(songs.map { it.toSong() }, 0) },
                    )
                    ActionRow(
                        icon = Icons.Rounded.Refresh,
                        label = stringResource(R.string.tg_start_scan),
                        onClick = onRescan,
                    )
                    // No delete. Removing a row from the channels table fed a
                    // "Chat not found" cascade that broke scans — the rows
                    // stay and the empty filter hides them instead. See
                    // ChannelsScreen.
                }

                // ── A playlist ───────────────────────────────────────────────
                playlistId != null -> {
                    ActionRow(
                        icon = Icons.Rounded.PlayArrow,
                        label = stringResource(R.string.playlists_play),
                        enabled = playable,
                        onClick = { onPlay(songs.map { it.toSong() }, 0) },
                    )
                    ActionRow(
                        icon = Icons.Rounded.Shuffle,
                        label = stringResource(R.string.playlists_shuffle),
                        enabled = playable,
                        onClick = {
                            if (playable) onPlay(songs.map { it.toSong() }, songs.indices.random())
                        },
                    )
                    ActionRow(
                        icon = Icons.Rounded.PushPin,
                        label = stringResource(
                            if (isPinned) R.string.playlists_unpin else R.string.playlists_pin,
                        ),
                        onClick = onTogglePin,
                    )
                    ActionRow(
                        icon = Icons.Rounded.Delete,
                        label = stringResource(R.string.playlists_delete),
                        // Opens the page, where the same delete sits behind the
                        // same confirmation the list already uses. A second
                        // confirmation written here would be a second place to
                        // keep right.
                        onClick = { onOpenPlaylist(playlistId) },
                    )
                }
            }

            Box(Modifier.height(12.dp))
        }
    }
}
