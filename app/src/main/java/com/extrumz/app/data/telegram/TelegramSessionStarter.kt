package com.extrumz.app.data.telegram

import com.extrumz.app.data.TrackLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
                .onFailure { TrackLog.w(TAG, "session start failed: ${it.message}") }
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
            }.onFailure { TrackLog.w(TAG, "session start failed: ${it.message}") }
        }
    }

    /**
     * Waits for Ready *without a budget*. First login — phone → code → 2FA —
     * routinely takes longer than any fixed timeout, and the auto-scan hangs
     * off this call: the 60s cap that used to be here returned null on a slow
     * first login, so [onReady] never ran and the list stayed empty until the
     * scan was started by hand. Nothing but cancellation stops this wait now;
     * [scope] and the callers' own scopes are what end it.
     */
    private suspend fun awaitReadyThen(block: suspend () -> Unit) {
        client.authState.first { it is TelegramAuthState.Ready }
        block()
    }

    private companion object {
        private const val TAG = "TelegramSession"
    }
}
