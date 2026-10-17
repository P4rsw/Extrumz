package com.extrumz.app.data.telegram

import com.extrumz.app.data.TrackLog
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

    /**
     * Leaves the QR flow and goes back to the phone-number one.
     *
     * Reaching `WaitOtherDeviceConfirmation` is one-way in TDLib: there is no
     * call that returns to `WaitPhoneNumber`, and dismissing the dialog only
     * hides it -- the authorization state is unchanged, so opening it again
     * shows the same QR. The only exit is to start authorization over, which
     * is safe here because nothing has been confirmed yet: no session exists
     * to lose.
     */
    /**
     * Starts authorization over, discarding a session that never got anywhere.
     *
     * [ensureStarted] returns the existing starter, which is right for its
     * name: it is the "make sure something is running" call, and calling it
     * from a button that means "try again" did nothing at all -- the starter
     * was already there, so the client was never asked to start again, and a
     * Closed client stayed closed behind a button that looked live.
     *
     * Both paths that follow a dead end go through here: the retry button on a
     * failed or closed state, and the switch out of QR.
     */
    @Synchronized
    fun restartAuthorization() {
        starter = null
        _showAuth.value = true
        ensureStarted()
    }

    fun usePhoneNumberInstead() {
        // Guarded on the state rather than trusted: logOut is the only call
        // TDLib offers that returns to WaitPhoneNumber, and on an authorized
        // client it would end a real session. Reaching it from the QR screen
        // means nothing has been confirmed, so there is no session to end --
        // and the guard makes that a property of the code rather than of the
        // caller.
        if (Telegram.client.authState.value !is TelegramAuthState.WaitingForQrScan) return
        scope.launch {
            runCatching { Telegram.client.logout() }
                .onFailure { TrackLog.w(TAG, "QR→phone switch failed: ${it.message}") }
            restartAuthorization()
        }
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
                scanJob?.cancel()
                scanJob = scope.launch {
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
                }
            },
        ).also { starter = it }
        scope.launch {
            runCatching { existing.start() }
                .onFailure { TrackLog.w(TAG, "client start failed: ${it.message}") }
        }
    }

    @Volatile
    private var scanJob: kotlinx.coroutines.Job? = null

    /** Kicks a full scan; no-op while one is already running (scanner guards itself). */
    fun startScan() {
        ensureStarted()
        scanJob?.cancel()
        scanJob = scope.launch {
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
