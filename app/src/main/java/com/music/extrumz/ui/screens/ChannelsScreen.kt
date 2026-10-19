package com.extrumz.app.ui.screens

import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.extrumz.app.R
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.db.entity.ChatSource
import com.extrumz.app.data.db.entity.ChatType
import com.extrumz.app.data.model.ROW_ART_PX
import com.extrumz.app.data.model.artworkAt
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.telegram.Covers
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.data.telegram.TelegramUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import com.extrumz.app.ui.components.ScanStatusBanner
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.PushPin
import com.extrumz.app.data.model.toSong
import androidx.compose.foundation.layout.height

/**
 * The scanned chats/channels, as a full-screen picker. Picking one narrows the
 * songs tab to that chat (tap it again there to clear) — or the search results,
 * when the picker was opened from Search's channel pill. When the listener has
 * not authorized yet, the authorize button is the first thing on screen — not
 * buried.
 */
@Composable
fun ChannelsScreen(
    contentPadding: PaddingValues,
    onChannelSelected: () -> Unit,
    /** Plays a channel's own tracks. Only playback needs the caller. */
    onPlayChannel: (List<com.extrumz.app.data.model.Song>, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val authState by Telegram.client.authState.collectAsState()
    val scan by Telegram.scanner.progress.collectAsState()
    val selected by TelegramUi.selectedChatId.collectAsState()

    // ALL rows, zero-song ones included — they stay in the DB and are
    // hidden by the toggle below instead (user report, 10-10): the earlier
    // approach of dropping them from the list is what fed the
    // "Chat not found" → untrack cascade that broke scans.
    val chats by TelegramDb.chatDao.observeAllChats()
        .collectAsState(initial = emptyList())
    val myUserId by TelegramAuthController.myUserId.collectAsState()
    val filterEmpty by AppSettings.filterEmptyChats.collectAsState()
    val baseVisibleChats = if (filterEmpty) {
        chats.filter { it.trackCount > 0 || (myUserId != null && it.chatId == myUserId) }
    } else {
        chats
    }
    val visibleChats = remember(baseVisibleChats, myUserId) {
        if (myUserId != null) {
            baseVisibleChats.sortedByDescending { it.chatId == myUserId }
        } else {
            baseVisibleChats
        }
    }
    // Restore-from-list (tdlib/td#3673): chats TDLib refuses to list ever
    // again are re-added BY ID from files/restore_chats.json. Only rows are
    // ever added here; nothing is deleted, and known ids are skipped.
    val context = LocalContext.current
    val restoreScope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    // The channel whose hold-menu is open. Holding a row is the gesture every
    // other list in this app answers, and it is where a channel's own verbs
    // live — there is no shelf for them any more.
    var holding by remember { mutableStateOf<ChatSource?>(null) }
    // The line reports what a restore just did, so it has to go away on its
    // own. Left up it read as current state — a "بازیابی…" still on screen
    // minutes later looks like a restore that hung, and every return to this
    // screen inherited whatever the last one said.
    LaunchedEffect(restoreStatus) {
        if (restoreStatus != null) {
            delay(6_000)
            restoreStatus = null
        }
    }
    val restoreFileExists = remember {
        File(context.filesDir, "restore_chats.json").exists()
    }

    // This screen starts at y=0, under the glass bars, so its topmost content
    // has to clear the system status bar itself — without this the scan button
    // sits at y≈39 where the status bar (0..127) swallows the tap (device
    // evidence, stage 2.5). The centred empty-state keeps its own sizing; the
    // whole column just shifts down together.
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        if (authState !is TelegramAuthState.Ready) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.tg_login_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { TelegramAuthController.requestAuthUi() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.tg_login)) }
                }
            }
        }
        if (authState is TelegramAuthState.Ready) {
            // Manual re-scan entry point; the first scan runs automatically
            // the moment authorization completes.
            TextButton(
                onClick = { TelegramAuthController.startScan() },
                enabled = !scan.isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.tg_start_scan)) }
        }

        // One banner, driven by the scanner's own stage and fraction. It
        // replaces a determinate-looking bar that had no value and an
        // "Attempt N" label that froze on its last number — the engine was
        // publishing both a stage and a 0..1 fraction the UI never read.
        // An error now carries a retry here instead of being a dead line.
        if (scan.isRunning || scan.lastError != null) {
            ScanStatusBanner(
                scan = scan,
                onRetry = if (scan.lastError != null) {
                    { TelegramAuthController.startScan() }
                } else {
                    null
                },
            )
        }

        // The filter button itself (user ask, 10-10): flipping it swaps the
        // zero-song rows in and out of the list ABOVE — nothing is ever
        // deleted from the DB, which is exactly why scans stay intact.
        // Shown only when there is something to filter (Ready + rows exist),
        // so a fresh login doesn't grow a dead toggle.
        if (authState is TelegramAuthState.Ready && chats.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (filterEmpty) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (filterEmpty) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        },
                    ),
                    modifier = Modifier.clip(CircleShape),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { AppSettings.setFilterEmptyChats(!filterEmpty) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterAlt,
                            contentDescription = null,
                            tint = if (filterEmpty) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (filterEmpty) R.string.tg_show_empty_chats
                                else R.string.tg_hide_empty_chats,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (filterEmpty) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // The count is the button's proof: it changes with the
                // toggle, so a filtered row is visibly still in the DB.
                Text(
                    stringResource(R.string.chats_count, visibleChats.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Restore button (user's missing-chats case, 10-10): TDLib stopped
        // listing ~40 live dialogs after its cache eviction bug (tdlib/td#3673),
        // so this re-adds them straight from the server, by id. Shown only
        // when the list file exists — no dead buttons for users without one.
        if (restoreFileExists && authState is TelegramAuthState.Ready) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                TextButton(
                    enabled = !restoring,
                    onClick = {
                        restoreScope.launch {
                            restoring = true
                            val existing = chats.map { it.chatId }.toSet()
                            restoreStatus = "در حال بازیابی…"
                            val result = runCatching { importRestoreList(context, existing) }
                                .getOrElse { "خطا در بازیابی: ${it.message}" }
                            restoring = false
                            restoreStatus = result
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tg_restore_missing))
                }
                restoreStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Three states, not two. The guard below tests `chats`, but the list
        // renders `visibleChats`, so with the empty filter on and every chat
        // at 0 tracks the screen fell through both branches and drew nothing
        // at all — no title, no action, no hint that a filter was hiding the
        // answer. The third branch says so, and undoes it.
        val allFilteredOut = chats.isNotEmpty() && visibleChats.isEmpty()
        if (allFilteredOut) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.tg_chats_all_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { AppSettings.setFilterEmptyChats(false) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.tg_show_all_chats))
                }
            }
        } else if (chats.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.tg_chats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (authState is TelegramAuthState.Ready && !scan.isRunning) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { TelegramAuthController.startScan() }) {
                        Text(stringResource(R.string.tg_start_scan))
                    }
                }
            }
        } else {
            LazyColumn(
                // Top is deliberately small. The outer Column already reserves
                // the status bar, and the Scaffold's own top inset was being
                // added on top of it here — two reservations for one bar, which
                // is the band of empty black the list opened with. The first
                // channel should start where the header above it ends.
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 96.dp,
                ),
            ) {
                items(visibleChats, key = { it.chatId }) { chat ->
                    val isSaved = myUserId != null && chat.chatId == myUserId
                    ChatRow(
                        chat = chat,
                        isSelected = selected == chat.chatId,
                        isSavedMessages = isSaved,
                        onLongPress = { holding = chat },
                        onClick = {
                            TelegramUi.selectedChatId.value =
                                if (selected == chat.chatId) null else chat.chatId
                            onChannelSelected()
                        },
                    )
                }
                if (authState is TelegramAuthState.Ready && !scan.isRunning) {
                    // The third copy of "Start scan" used to live here, at the
                    // bottom of the list. It is the same action as the button
                    // at the top of the page, which is visible without a
                    // scroll — so the page had one primary action drawn three
                    // times, and the one users actually found was the one they
                    // had to scroll to. Removed; the top button is the entry
                    // point and the empty state keeps its own.
                }
            }
        }
    }
        holding?.let { target ->
            val songs by TelegramDb.trackDao.observeByChat(target.chatId)
                .collectAsState(initial = emptyList())
            ChannelHoldMenu(
                chat = target,
                isSelected = selected == target.chatId,
                trackCount = songs.size,
                onDismiss = { holding = null },
                onFilter = {
                    TelegramUi.selectedChatId.value =
                        if (selected == target.chatId) null else target.chatId
                    holding = null
                    // And go where the filter shows. Setting it and leaving
                    // the user on the picker made the row look broken: the
                    // list it filters is a different screen.
                    onChannelSelected()
                },
                onPlay = {
                    if (songs.isNotEmpty()) {
                        holding = null
                        onPlayChannel(songs.map { it.toSong() }, target.title)
                    }
                },
                onRescan = {
                    TelegramAuthController.startScan()
                    // Close, or the scan's banner — which is on the screen
                    // underneath — is started behind an open sheet and the
                    // row reads as having done nothing.
                    holding = null
                },
            )
        }
}

/**
 * The exception line shown under the scan-failure title: blank stays hidden,
 * and anything longer than [max] characters is cut with an ellipsis so a
 * stack-length message can neither crash the banner nor push it around.
 */
internal fun displayErrorDetail(detail: String?, max: Int = 120): String? {
    val text = detail?.trim().orEmpty()
    if (text.isEmpty()) return null
    return if (text.length > max) text.take(max) + "…" else text
}


@Composable
private fun ChatRow(
    chat: ChatSource,
    isSelected: Boolean,
    isSavedMessages: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val photoUrl = chat.photoUrl
        if (!isSavedMessages) {
            LaunchedEffect(chat.chatId, photoUrl) {
                delay(250)
                Covers.improveChatPhoto(chat.chatId, photoUrl)
            }
        }
        if (isSavedMessages) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2AABEE)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bookmark,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (photoUrl.isNullOrBlank()) {
                    Text(
                        chat.title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AsyncImage(
                        model = photoUrl.artworkAt(ROW_ART_PX),
                        contentDescription = chat.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (isSavedMessages) stringResource(R.string.saved_messages) else chat.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "${chat.trackCount} آهنگ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Re-add the chats TDLib's lists have permanently lost (tdlib/td#3673):
 * every id in files/restore_chats.json is fetched straight from the SERVER
 * by id (CreatePrivateChat/CreateSupergroupChat, which work even when the
 * chat is in no local list) and inserted as a tracked row, so the next
 * scan pulls its history back. Known ids are skipped and nothing is ever
 * deleted — this is a pure restore path.
 *
 * Returns "restored X, still missing Y" text for the UI.
 */
private suspend fun importRestoreList(context: Context, existing: Set<Long>): String {
    val file = File(context.filesDir, "restore_chats.json")
    if (!file.exists()) return "فایل restore_chats.json در پوشه‌ی برنامه پیدا نشد"
    val arr = JSONArray(file.readText())
    var added = 0
    var gone = 0
    for (i in 0 until arr.length()) {
        val entry = arr.optJSONObject(i) ?: continue
        val id = entry.optLong("chatId", 0L)
        if (id == 0L || id in existing) continue
        var info = Telegram.client.getChatInfo(id)
        if (info == null) {
            // tdlib/td#3673: TDLib won't keep or re-load these chats itself,
            // but the user's chat list still exists on the SERVER — search it
            // by the backed-up title and match strictly by id, so a name
            // collision with an unrelated chat can never be restored.
            val title = entry.optString("title", "")
            if (title.isNotEmpty() && Telegram.client.searchChatOnServer(title).contains(id)) {
                // The server-side load just pulled the chat into TDLib's
                // cache, so a plain GetChat resolves now.
                info = Telegram.client.getChatInfo(id)
            }
        }
        if (info == null) {
            gone++
            continue
        }
        TelegramDb.chatDao.upsert(
            ChatSource(
                chatId = id,
                title = info.title,
                username = info.username,
                photoUrl = info.photoUrl,
                type = when {
                    info.isChannel -> ChatType.CHANNEL
                    info.isGroup -> ChatType.GROUP
                    else -> ChatType.PRIVATE
                },
                isTracked = true,
            ),
        )
        added++
    }
    // Anything restored is an unknown-history chat again — kick a scan so
    // its tracks come back without the user having to press twice.
    if (added > 0) TelegramAuthController.startScan()
    val suffix = if (gone > 0) "، $gone تا دیگر وجود ندارد" else ""
    return "بازیابی: $added چت برگشت$suffix"
}


/** The menu behind a held channel row: filter, play, rescan — and no delete. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChannelHoldMenu(
    chat: ChatSource,
    isSelected: Boolean,
    trackCount: Int,
    onDismiss: () -> Unit,
    onFilter: () -> Unit,
    onPlay: () -> Unit,
    onRescan: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        com.extrumz.app.ui.util.ProvideLocaleDirection {
            val myUserId by TelegramAuthController.myUserId.collectAsState()
            val isSaved = myUserId != null && chat.chatId == myUserId
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    text = if (isSaved) stringResource(R.string.saved_messages) else chat.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.playlists_count, trackCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            com.extrumz.app.ui.components.ActionRow(
                icon = Icons.Rounded.FilterAlt,
                label = stringResource(
                    if (isSelected) R.string.chan_clear_filter else R.string.chan_filter_by,
                ),
                onClick = onFilter,
            )
            com.extrumz.app.ui.components.ActionRow(
                icon = Icons.Rounded.PlayArrow,
                label = stringResource(R.string.chan_play_all),
                enabled = trackCount > 0,
                onClick = onPlay,
            )
            com.extrumz.app.ui.components.ActionRow(
                icon = Icons.Rounded.Refresh,
                label = stringResource(R.string.tg_start_scan),
                onClick = onRescan,
            )
            // No delete, deliberately: rows removed from the channels table
            // once fed a "Chat not found" cascade that broke scans. The rows
            // stay and the empty filter hides them instead.
            Spacer(Modifier.height(12.dp))
        }
    }
}
