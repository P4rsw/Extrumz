package com.music.bitchord.data.telegram

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the shared TDLib client with the baked-in credentials, auto-scans
 * once authorization completes, and owns the auth dialog's visibility.
 *
 * The deep-link half ([onLoginLink]) exists for `tg://login?token=` links:
 * TDLib only accepts the confirmation after its parameters have executed, so
 * the link is held until the client reaches a waiting state and only then
 * passed to [MusicTelegramClient.confirmLoginLink]. Phone entry stays the
 * fallback whenever the token is rejected.
 */
object TelegramAuthController {

    private const val TAG = "TelegramAuth"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _showAuth = MutableStateFlow(false)
    /** Whether the auth dialog should be on screen. */
    val showAuth: StateFlow<Boolean> = _showAuth

    @Volatile
    private var starter: TelegramSessionStarter? = null

    @Volatile
    private var pendingLink: String? = null

    fun requestAuthUi() {
        ensureStarted()
        _showAuth.value = true
    }

    fun dismissAuthUi() {
        _showAuth.value = false
    }

    /** Boots the client exactly once; the first scan runs when Ready arrives. */
    @Synchronized
    fun ensureStarted() {
        val existing = starter ?: TelegramSessionStarter(
            client = Telegram.client,
            credentialsProvider = { TelegramConfig.credentials() },
            onReady = {
                // Fills the songs tab without the listener having to ask.
                // fullScan surfaces its own failures on ScanProgress; the
                // runCatching log here catches what slips past it (the
                // scanner guards and waits internally), so a silent death
                // of the auto-scan can't repeat unnoticed.
                TrackLog.d(TAG, "authorization ready; starting auto-scan")
                runCatching {
                    // Live indexing starts BEFORE the first scan: a message
                    // arriving mid-scan is indexed now, not at the next pass.
                    Telegram.scanner.startLiveIndexing(scope)
                    Telegram.scanner.fullScan()
                    // Silent second pass: the auto-scan races TDLib's dialog
                    // sync, so the least-active chats answer empty and stay at
                    // bound 0 launch after launch — nothing fails, nothing
                    // logs. quietRepass retries them once the sync settles.
                    Telegram.scanner.quietRepass()
                }.onFailure { TrackLog.w(TAG, "auto-scan failed: ${it.message}") }
            },
        ).also { starter = it }
        scope.launch {
            runCatching { existing.start() }
                .onFailure { TrackLog.w(TAG, "client start failed: ${it.message}") }
        }
    }

    /** Kicks a full scan; no-op while one is already running (scanner guards itself). */
    fun startScan() {
        ensureStarted()
        scope.launch {
            runCatching {
                Telegram.scanner.fullScan()
                Telegram.scanner.quietRepass()
            }.onFailure { TrackLog.w(TAG, "manual scan failed: ${it.message}") }
        }
    }

    fun onLoginLink(link: String) {
        ensureStarted()
        pendingLink = link
        scope.launch {
            withTimeoutOrNull(30_000) {
                Telegram.client.authState.first {
                    it is TelegramAuthState.WaitingForPhoneNumber ||
                        it is TelegramAuthState.WaitingForQrScan ||
                        it is TelegramAuthState.Ready ||
                        it is TelegramAuthState.Failed
                }
            }
            val held = pendingLink ?: return@launch
            pendingLink = null
            when (Telegram.client.authState.value) {
                is TelegramAuthState.WaitingForPhoneNumber,
                is TelegramAuthState.WaitingForQrScan ->
                    runCatching { Telegram.client.confirmLoginLink(held) }
                        .onFailure { TrackLog.w(TAG, "deep-link confirm failed: ${it.message}") }
                else -> Unit
            }
        }
    }
}
