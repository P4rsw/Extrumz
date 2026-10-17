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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.extrumz.app.data.model.ROW_ART_PX
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.artworkAt
import com.extrumz.app.data.sources.SourceKind
import com.extrumz.app.data.sources.SourceRegistry
import com.extrumz.app.data.telegram.Covers
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.data.telegram.TelegramUi
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import com.extrumz.app.ui.util.isRtlReading
import com.extrumz.app.ui.util.ProvideLocaleDirection
import com.extrumz.app.ui.components.ScanStatusBanner
import com.extrumz.app.data.model.toSong
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/**
 * The offline Telegram library: every track the scanner has indexed in Room,
 * searchable in memory, optionally narrowed to one chat by the channels tab.
 * The header dot shows whether TDLib is authorized — the list itself works
 * fully offline, which is the point of having scanned it.
 */

/** Scroll-settle time before the prefetch window is warmed. */
private const val PREFETCH_SETTLE_MS = 350L

/** Rows warmed ahead of the viewport once scrolling settles. */
private const val PREFETCH_WINDOW = 24

/**
 * How long a row must stay composed before it may touch the network. A fling
 * unbinds rows in well under this, so fast scrolling costs no requests at all
 * — only rows the reader actually stops on fetch, and the settle-prefetch
 * covers the rows they are about to reach.
 */
private const val ROW_FETCH_DELAY_MS = 250L

@OptIn(FlowPreview::class)
@Composable
fun SongsScreen(
    contentPadding: PaddingValues,
    currentSong: Song?,
    onSongClick: (List<Song>, Int) -> Unit,
    /**
     * A held row. Every other list in the app answers a hold with the song
     * menu; this one — the main list, the one people actually use — was the
     * only one that did not, so the menu was unreachable from the screen it
     * is most wanted on.
     */
    onSongLongPress: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val chatFilter by TelegramUi.selectedChatId.collectAsState()
    val authState by Telegram.client.authState.collectAsState()
    val scan by Telegram.scanner.progress.collectAsState()

    // Sampled while a scan runs. Every page the scanner writes invalidates the
    // tracks table, so a rescan of a ten-thousand-song library resolved this
    // query — all of it, off disk — and rebuilt the list on every page, six
    // workers at a time. That is what the lag was. Twice a second is still
    // visibly live and costs a fraction of it; `sample` rather than `debounce`
    // because intermediate states should be dropped, not deferred.
    val isScanning by Telegram.scanner.progress
        .map { it.isRunning }
        .collectAsState(initial = false)
    val allTracks by produceState<List<Track>>(initialValue = emptyList(), isScanning) {
        val source = TelegramDb.trackDao.observeAll()
        val flow = if (isScanning) source.sample(500) else source
        flow.collect { value = it }
    }

    val filteredChatName by produceState<String?>(initialValue = null, chatFilter) {
        value = chatFilter?.let { id -> TelegramDb.chatDao.getById(id)?.title }
    }

    // Which rows are actually in the filtered chat. From `track_sources`, not
    // from `sourceChatId`: a song can be found in six chats and the row
    // remembers one, so filtering on the row's own field showed it in exactly
    // one of them. The channel filter looked broken while the data was right.
    // Ordered, not just a set: the ids arrive newest-first for that chat, and
    // the list is rebuilt in that order below. `allTracks` is ordered by
    // `dateAdded DESC` — the keeper row's own timestamp — which is a different
    // question. A song downloaded elsewhere today came first in a channel it
    // was posted to years ago, and one indexed long ago sank to where its
    // keeper came from. Ordering is the channel's, so it has to come from the
    // channel's own message ids.
    val chatOrder by produceState<List<String>>(emptyList(), chatFilter) {
        value = chatFilter?.let { id ->
            TelegramDb.trackDao.observeIdsInChat(id).first()
        } ?: emptyList()
    }
    val tracks = filteredTracks(allTracks, query, chatFilter, chatOrder)
    val songs = remember(tracks) { tracks.map(Track::toSong) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Top inset only. Applying the whole Scaffold inset as a modifier
            // shrank the scrollable area on every side, so the region outside
            // it — the band behind the mini-player and the nav bar the user
            // sees — was the bare window background, i.e. flat black. The
            // Library page passes the same inset down to its list instead and
            // its content runs edge to edge, which is why one looked open and
            // the other looked like a black gap. The list keeps its own 96dp
            // bottom padding, so nothing scrolls under the bars unseen.
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
    // The whole header mirrors for Persian: the connection line and the
    // search field both sit at the reading edge, and a provider is the
    // only thing that can move both without flipping the tabs below.
    ProvideLocaleDirection {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // The connection line sits at the reading edge: the right for a
                // Persian UI, the left for an English one. `Arrangement.End` would
                // be direction-aware on its own, but this window is pinned to LTR —
                // `supportsRtl` is off and stays off — so the edge is picked here
                // from the locale instead of inherited from the view.
                horizontalArrangement = Arrangement.Start,
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
                placeholder = {
                    Text(
                        stringResource(R.string.tg_search_hint),
                        // Persian reads right-to-left inside a screen that stays
                        // left-to-right, so the edge is asked for here rather than
                        // inherited. English is left where it falls.
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Start,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
    }

        // One banner for both states, driven by the scanner's own stage and
        // fraction — the same component the Channels page uses, so the two
        // screens cannot drift apart again. This replaces an indeterminate bar
        // and an "Attempt N" label that froze on its last value, and it gives
        // a failed scan a retry instead of leaving a red line with no action.
        if (scan.isRunning || scan.lastError != null) {
            ScanStatusBanner(
                scan = scan,
                onDismiss = if (scan.lastError != null) {
                    { Telegram.scanner.dismissError() }
                } else {
                    null
                },
                onRetry = if (scan.lastError != null) {
                    { TelegramAuthController.startScan() }
                } else {
                    null
                },
            )
        }

        if (songs.isEmpty()) {
            // Mirrored like the header above. The direction provider only
            // covered the header, so an empty library showed a Persian
            // paragraph whose wrapped lines were aligned to the left — the
            // last edge a Persian reader looks at — while every other line on
            // the screen was right-aligned.
            ProvideLocaleDirection {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Two different situations used to share one line, and the
                // line was written for only one of them: a signed-in user with
                // an empty library was told to sign in, and offered no button
                // at all. The rule is that an empty state carries an action.
                val signedIn = authState is TelegramAuthState.Ready
                Text(
                    stringResource(
                        if (signedIn) R.string.tg_empty_scan_hint
                        else R.string.tg_empty_hint
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                when {
                    !signedIn -> Button(onClick = { TelegramAuthController.requestAuthUi() }) {
                        Text(stringResource(R.string.tg_login))
                    }
                    !scan.isRunning -> Button(onClick = { TelegramAuthController.startScan() }) {
                        Text(stringResource(R.string.tg_start_scan))
                    }
                }
            }
            }
        } else {
            val listState = rememberLazyListState()
            val songsNow by rememberUpdatedState(songs)
            // F8: covers arrive one screenful early instead of one row at a
            // time. When scrolling settles (350ms without a new index), the
            // visible-plus-ahead window is warmed in an app-lifetime scope —
            // the download finishes even if the user scrolls on, so nothing is
            // paid twice, and rows that already carry full-size art are
            // skipped before any RPC. The row effects below still cover rows
            // the moment they bind, so an idle screen never waits on a timer.
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .debounce(PREFETCH_SETTLE_MS)
                    .collect { first ->
                        Covers.prefetchTrackCovers(
                            songsNow.drop(first).take(PREFETCH_WINDOW)
                                .map { Covers.Row(it.videoId, it.thumbnailUrl, it.title, it.artist) },
                        )
                    }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                itemsIndexed(
                    songs,
                    // A stable key: without one, every Flow emission during a
                    // rescan rebinds rows positionally, so covers swap slots
                    // for a frame — the "mixed up" look after a manual scan.
                    key = { _, song -> song.videoId },
                ) { index, song ->
                    // F8: a row with no cover fetches it once; Tier 2 (full
                    // size) lands first so the 160px slot is drawn from the
                    // real cover, with the inline minithumbnail only as the
                    // fallback. A URL already on the row short-circuits
                    // before any RPC. The delay is the fling guard: a row
                    // passed at speed unbinds before it expires, so only rows
                    // that stay put on the screen ever ask the network.
                    LaunchedEffect(song.videoId, song.thumbnailUrl, isScanning) {
                        // Nothing while a scan is indexing: every row arriving
                        // is a row the user has not looked at yet, and the
                        // fetch queue it fills is the one the covers they ARE
                        // looking at are waiting in. The row fetches on the
                        // next pass once the scan settles.
                        if (isScanning) return@LaunchedEffect
                        delay(ROW_FETCH_DELAY_MS)
                        Covers.improveTrackCover(song.videoId, song.thumbnailUrl, song.title, song.artist)
                    }
                    SongRow(
                        title = song.title,
                        subtitle = song.subtitle(),
                        coverUrl = song.thumbnailUrl,
                        isCurrent = currentSong?.videoId == song.videoId,
                        onClick = { onSongClick(songs, index) },
                        onLongPress = { onSongLongPress(song) },
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
    onLongPress: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
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

/**
 * [chatTrackIds] is every row `track_sources` places in the filtered chat, and
 * it is the membership test rather than `track.sourceChatId`. One row can
 * belong to many chats — six, for a song shared around — and `sourceChatId`
 * remembers only whichever chat the keeper happened to come from, so a song
 * was reachable from one of its channels and invisible in the rest.
 */
private fun filteredTracks(
    all: List<Track>,
    query: String,
    chatId: Long?,
    chatOrder: List<String>,
): List<Track> {
    val q = query.trim()
    if (q.isEmpty() && chatId == null) return all
    val rank = if (chatId == null) null else chatOrder.withIndex().associate { it.value to it.index }
    val matched = all.filter { track ->
        (chatId == null || rank!!.containsKey(track.fileUniqueId)) &&
            (q.isEmpty() ||
                track.title.contains(q, ignoreCase = true) ||
                track.performer.contains(q, ignoreCase = true) ||
                track.album.contains(q, ignoreCase = true))
    }
    if (rank == null) return matched
    return matched.sortedBy { rank[it.fileUniqueId] ?: Int.MAX_VALUE }
}

