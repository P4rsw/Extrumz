package com.extrumz.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.artworkAt
import com.extrumz.app.data.model.toSong
import com.extrumz.app.ui.util.ProvideLocaleDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.extrumz.app.data.LikeState
import com.extrumz.app.data.model.LikeStatus

/**
 * Everything the user has liked.
 *
 * The like row in the song menu wrote a flag with nowhere to read it back —
 * no list was filtered by it and no screen counted it. This is that place.
 *
 * It is a plain list rather than a fifth tab: a tab is a place you travel to
 * often enough to want in the bar, and this is a view of the library you
 * reach for now and then. It sits beside Channels and Playlists, which is
 * what it is — another way of looking at the same tracks.
 */
@Composable
fun LikedScreen(
    listState: LazyListState,
    contentPadding: PaddingValues,
    onPlay: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideLocaleDirection {
        // LikeState.overrides, not `tracks.isFavorite`. The heart writes a
        // rating through LikeState; `isFavorite` is a separate column nothing
        // in this app ever sets, so reading it returned an empty list no
        // matter how many songs were liked.
        //
        // The rating is held for the session — LikeState is cleared on sign
        // out — so this list is what has been liked since then. Writing it
        // through to the tracks column is the next step, and a bigger one.
        val overrides by LikeState.overrides.collectAsState()
        val likedIds = remember(overrides) {
            overrides.filterValues { it == LikeStatus.LIKE }.keys
        }
        var songs by remember(likedIds) { mutableStateOf<List<Song>>(emptyList()) }
        LaunchedEffect(likedIds) {
            songs = likedIds.mapNotNull { videoId -> songFor(videoId) }
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            Text(
                text = stringResource(R.string.playlists_count, songs.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (songs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.liked_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.liked_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = contentPadding.calculateBottomPadding() + 96.dp,
                    ),
                ) {
                    itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlay(songs, index) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                val cover = song.thumbnailUrl
                                if (cover.isNullOrBlank()) {
                                    Text(
                                        song.title.take(1),
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
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * The local row behind a rated video id, as a Song.
 *
 * A Telegram row's id is `src:<configId>::tg_<chatId>_<messageId>`, so the
 * pair can be read back out of it. A rating taken on anything not in the
 * tracks table — a search result, a video — has no row to show and is skipped
 * rather than rendered as a blank line.
 */
private suspend fun songFor(videoId: String): Song? {
    val tail = videoId.substringAfterLast("::", missingDelimiterValue = "")
        .ifEmpty { videoId }
    if (!tail.startsWith("tg_")) return null
    val parts = tail.removePrefix("tg_").split("_")
    val chatId = parts.getOrNull(0)?.toLongOrNull() ?: return null
    val messageId = parts.getOrNull(1)?.toLongOrNull() ?: return null
    return TelegramDb.trackDao.getByChatAndMessage(chatId, messageId)?.toSong()
}
