package com.music.bitchord.data.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the TDLib client once credentials are available and keeps the session alive.
 * [onReady] is invoked exactly once after [TelegramAuthState.Ready] so callers
 * (e.g. the new-audio watcher) do not race the authorization flow.
 */
class TelegramSessionStarter(
    private val client: MusicTelegramClient,
    private val credentialsProvider: () -> TelegramCredentials?,
    private val onReady: suspend () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startAsync() {
        scope.launch {
            runCatching { start() }
        }
    }

    suspend fun start() {
        when (client.authState.value) {
            is TelegramAuthState.Ready -> {
                onReady()
                return
            }
            is TelegramAuthState.Initializing -> {
                awaitReadyThen(onReady)
                return
            }
            else -> Unit
        }
        val credentials = credentialsProvider()
            ?: run {
                // Leave Uninitialized so UI can show onboarding to collect api_id/hash.
                return
            }
        client.start(credentials)
        awaitReadyThen(onReady)
    }

    fun provideAndStart(credentials: TelegramCredentials) {
        scope.launch {
            runCatching {
                client.start(credentials)
                awaitReadyThen(onReady)
            }
        }
    }

    private suspend fun awaitReadyThen(block: suspend () -> Unit) {
        val ready = withTimeoutOrNull(60_000) {
            client.authState.first { it is TelegramAuthState.Ready }
        }
        if (ready != null) block()
    }
}
