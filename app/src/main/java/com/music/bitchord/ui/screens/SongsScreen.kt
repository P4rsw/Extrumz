package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.db.entity.Track
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.telegram.Covers
import com.music.bitchord.data.telegram.Telegram
import com.music.bitchord.data.telegram.TelegramAuthController
import com.music.bitchord.data.telegram.TelegramAuthState
import com.music.bitchord.data.telegram.TelegramUi
import java.util.Locale

/**
 * The offline Telegram library: every track the scanner has indexed in Room,
 * searchable in memory, optionally narrowed to one chat by the channels tab.
 * The header dot shows whether TDLib is authorized — the list itself works
 * fully offline, which is the point of having scanned it.
 */
@Composable
fun SongsScreen(
    contentPadding: PaddingValues,
    currentSong: Song?,
    onSongClick: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val chatFilter by TelegramUi.selectedChatId.collectAsState()
    val authState by Telegram.client.authState.collectAsState()
    val scan by Telegram.scanner.progress.collectAsState()

    val allTracks by produceState<List<Track>>(initialValue = emptyList()) {
        TelegramDb.trackDao.observeAll().collect { value = it }
    }

    val filteredChatName by produceState<String?>(initialValue = null, chatFilter) {
        value = chatFilter?.let { id -> TelegramDb.chatDao.getById(id)?.title }
    }

    val tracks = filteredTracks(allTracks, query, chatFilter)
    val songs = remember(tracks) { tracks.map(Track::toSong) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val connected = authState is TelegramAuthState.Ready
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (connected) stringResource(R.string.tg_connected)
                else stringResource(R.string.tg_login),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = !connected) {
                    TelegramAuthController.requestAuthUi()
                },
            )
            if (chatFilter != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "✕ " + (filteredChatName ?: stringResource(R.string.tg_all_chats)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { TelegramUi.selectedChatId.value = null },
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.tg_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        if (scan.isRunning) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.tg_scanning) +
                    if (scan.attempts > 0) {
                        " — " + stringResource(R.string.tg_scan_attempt, scan.attempts)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // A scan that ended empty (chats never loaded) says so here rather
        // than leaving the empty-state hint below to imply there is no music.
        // Same two-line treatment as ChannelsScreen (review parity fix): the
        // translated title, then the real cause underneath in a muted tone.
        if (!scan.isRunning && scan.lastError != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.tg_scan_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                displayErrorDetail(scan.lastErrorDetail)?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (songs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.tg_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (authState !is TelegramAuthState.Ready) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { TelegramAuthController.requestAuthUi() }) {
                        Text(stringResource(R.string.tg_login))
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                itemsIndexed(songs) { index, song ->
                    // F8: a row with no cover fetches it once; Tier 2 (full
                    // size) lands first so the 160px slot is drawn from the
                    // real cover, with the inline minithumbnail only as the
                    // fallback. A URL already on the row short-circuits
                    // before any RPC, so a fling through the list costs
                    // nothing.
                    LaunchedEffect(song.videoId, song.thumbnailUrl) {
                        Covers.improveTrackCover(song.videoId, song.thumbnailUrl)
                    }
                    SongRow(
                        title = song.title,
                        subtitle = song.subtitle(),
                        coverUrl = song.thumbnailUrl,
                        isCurrent = currentSong?.videoId == song.videoId,
                        onClick = { onSongClick(songs, index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    title: String,
    subtitle: String,
    coverUrl: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // F8: the cover sits beside the name like a media list should. The
        // placeholder holds the slot so a late fetch never reflows the row.
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Indexed locally: playable without a network.
        Box(
            Modifier
                .size(7.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

private fun Song.subtitle(): String = listOfNotNull(
    artist.ifBlank { null },
    durationText,
).joinToString(" • ")

private fun filteredTracks(
    all: List<Track>,
    query: String,
    chatId: Long?,
): List<Track> {
    val q = query.trim()
    if (q.isEmpty() && chatId == null) return all
    return all.filter { track ->
        (chatId == null || track.sourceChatId == chatId) &&
            (q.isEmpty() ||
                track.title.contains(q, ignoreCase = true) ||
                track.performer.contains(q, ignoreCase = true) ||
                track.album.contains(q, ignoreCase = true))
    }
}

private fun Track.toSong(): Song {
    val telegramConfigId = SourceRegistry.configs.value
        .firstOrNull { it.kind == SourceKind.TELEGRAM }?.id
    val trackId = "tg_${sourceChatId}_${sourceMessageId}"
    return Song(
        videoId = telegramConfigId?.let { SourceRegistry.trackKey(it, trackId) } ?: trackId,
        title = title.ifBlank { fileId },
        artist = performer.ifBlank { album.ifBlank { "—" } },
        thumbnailUrl = coverUrl.ifBlank { null },
        durationText = if (duration > 0) {
            String.format(Locale.ROOT, "%d:%02d", duration / 60, duration % 60)
        } else {
            null
        },
    )
}
