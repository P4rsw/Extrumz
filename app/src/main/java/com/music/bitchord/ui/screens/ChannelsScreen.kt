package com.music.bitchord.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.music.bitchord.R
import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.db.entity.ChatSource
import com.music.bitchord.data.db.entity.ChatType
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.telegram.Covers
import com.music.bitchord.data.telegram.Telegram
import com.music.bitchord.data.telegram.TelegramAuthController
import com.music.bitchord.data.telegram.TelegramAuthState
import com.music.bitchord.data.telegram.TelegramUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

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
    val filterEmpty by AppSettings.filterEmptyChats.collectAsState()
    val visibleChats = if (filterEmpty) {
        chats.filter { it.trackCount > 0 }
    } else {
        chats
    }
    // Restore-from-list (tdlib/td#3673): chats TDLib refuses to list ever
    // again are re-added BY ID from files/restore_chats.json. Only rows are
    // ever added here; nothing is deleted, and known ids are skipped.
    val context = LocalContext.current
    val restoreScope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
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

        if (scan.isRunning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.tg_scanning) +
                    if (scan.attempts > 0) {
                        " — " + stringResource(R.string.tg_scan_attempt, scan.attempts)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Surfaced rather than swallowed: a scan that ended with nothing
        // loaded has to say so, or the empty list below reads as "no music".
        if (!scan.isRunning && scan.lastError != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    stringResource(R.string.tg_scan_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                // The title stays the generic (translated) line; the real
                // exception — a malformed DB, a failed RPC — is what makes the
                // failure actionable, so it runs underneath in a muted tone.
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
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                TextButton(onClick = { AppSettings.setFilterEmptyChats(!filterEmpty) }) {
                    Icon(
                        imageVector = Icons.Rounded.FilterAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (filterEmpty) R.string.tg_show_empty_chats
                            else R.string.tg_hide_empty_chats,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.weight(1f))
                // The count is the button's proof: it changes with the
                // toggle, so a filtered row is visibly still in the DB.
                Text(
                    "${visibleChats.size} چت",
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

        if (chats.isEmpty()) {
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
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 96.dp,
                ),
            ) {
                items(visibleChats, key = { it.chatId }) { chat ->
                    ChatRow(
                        chat = chat,
                        isSelected = selected == chat.chatId,
                        onClick = {
                            TelegramUi.selectedChatId.value =
                                if (selected == chat.chatId) null else chat.chatId
                            onChannelSelected()
                        },
                    )
                }
                if (authState is TelegramAuthState.Ready && !scan.isRunning) {
                    item {
                        TextButton(
                            onClick = { TelegramAuthController.startScan() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) { Text(stringResource(R.string.tg_start_scan)) }
                    }
                }
            }
        }
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
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val photoUrl = chat.photoUrl
        // F8: channels scanned before photoUrl existed (or with no inline
        // bytes) fetch their avatar once, the first time the row is on screen —
        // and Tier 2 (full-size) lands first, so a row carrying only the inline
        // minithumbnail still upgrades to covers/hq_*. One key per chat,
        // deduped — a fling through the list costs one fetch per channel, ever,
        // and nothing at all once the row has an HQ URL.
        LaunchedEffect(chat.chatId, photoUrl) {
            // Fling guard, same as the songs list: a row passed at speed
            // unbinds before this expires, so a fast scroll through the
            // channel list costs no requests — rows that stop on screen
            // fetch, and the auth wait inside Covers resumes them the moment
            // TDLib connects.
            delay(250)
            Covers.improveChatPhoto(chat.chatId, photoUrl)
        }
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
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
