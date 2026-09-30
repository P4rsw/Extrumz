package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.db.entity.ChatSource
import com.music.bitchord.data.telegram.Telegram
import com.music.bitchord.data.telegram.TelegramAuthController
import com.music.bitchord.data.telegram.TelegramAuthState
import com.music.bitchord.data.telegram.TelegramUi

/**
 * The scanned chats/channels. Picking one narrows the songs tab to that
 * chat (tap it again there to clear). When the listener has not authorized
 * yet, the authorize button is the first thing on screen — not buried.
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

    val chats by TelegramDb.chatDao.observeAllByTrackCount()
        .collectAsState(initial = emptyList())

    Column(modifier = modifier.fillMaxSize()) {
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
                stringResource(R.string.tg_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                items(chats, key = { it.chatId }) { chat ->
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
