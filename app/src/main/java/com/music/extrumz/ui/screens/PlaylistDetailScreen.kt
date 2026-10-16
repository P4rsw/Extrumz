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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.extrumz.app.R
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.artworkAt
import com.extrumz.app.ui.util.ProvideLocaleDirection
import kotlinx.coroutines.launch
import com.extrumz.app.data.model.toSong

/**
 * One playlist: what is in it, and what can be done to it.
 *
 * The tracks are read straight from the DAO as `Track`s and mapped through the
 * shared `toSong`, which also carries `fileUniqueId` on `setVideoId` — removal
 * deletes on `trackFileUniqueId`, so acting on a `videoId` would delete
 * nothing and say nothing.
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onPlay: (List<Song>, Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideLocaleDirection {
        val dao = TelegramDb.playlistDao
        val playlist by remember(playlistId) {
            kotlinx.coroutines.flow.flow { emit(dao.getById(playlistId)) }
        }.collectAsState(initial = null)
        val tracks by dao.observeTracks(playlistId)
            .collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()

        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = playlist?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.playlists_count, tracks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (tracks.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlaylistAction(
                        icon = Icons.Rounded.PlayArrow,
                        label = stringResource(R.string.playlists_play),
                        primary = true,
                        onClick = { onPlay(tracks.map { it.toSong() }, 0) },
                    )
                    PlaylistAction(
                        icon = Icons.Rounded.Shuffle,
                        label = stringResource(R.string.playlists_shuffle),
                        primary = false,
                        onClick = {
                            val songs = tracks.map { it.toSong() }
                            if (songs.isNotEmpty()) onPlay(songs, songs.indices.random())
                        },
                    )
                }
            }

            if (tracks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.playlists_no_tracks),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.playlists_no_tracks_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.playlists_back_to_list))
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
                    itemsIndexed(tracks, key = { _, track -> track.fileUniqueId }) { index, track ->
                        TrackRow(
                            track = track,
                            canMoveUp = index > 0,
                            canMoveDown = index < tracks.lastIndex,
                            onClick = { onPlay(tracks.map { it.toSong() }, index) },
                            onMoveUp = { scope.launch { dao.move(playlistId, index, index - 1) } },
                            onMoveDown = { scope.launch { dao.move(playlistId, index, index + 1) } },
                            onRemove = {
                                scope.launch { dao.removeTrack(playlistId, track.fileUniqueId) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (primary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One row of the playlist.
 *
 * Reordering is two menu items rather than a drag. The DAO's `move` is by
 * index, which a menu can address exactly, and a drag needs its own gesture
 * surface, autoscroll and a settled-index contract — worth doing, but not
 * worth doing before the plain path works.
 */
@Composable
private fun TrackRow(
    track: Track,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = track.coverUrl
            if (cover.isBlank()) {
                Text(
                    track.title.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AsyncImage(
                    model = cover.artworkAt(88),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { track.fileId },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.performer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                if (canMoveUp) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlists_move_up)) },
                        leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null) },
                        onClick = { menuOpen = false; onMoveUp() },
                    )
                }
                if (canMoveDown) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlists_move_down)) },
                        leadingIcon = { Icon(Icons.Rounded.ArrowDownward, contentDescription = null) },
                        onClick = { menuOpen = false; onMoveDown() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlists_remove_track)) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}
