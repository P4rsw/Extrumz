package com.extrumz.app.data.telegram

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared state machine and helpers for platform TDLib clients.
 *
 * Method bodies that touch TdApi live in [AndroidMusicTelegramClient]; this
 * base owns the common auth/connection/new-audio plumbing so every consumer
 * observes one consistent state machine.
 */
abstract class BaseMusicTelegramClient : MusicTelegramClient {

    protected val _authState =
        MutableStateFlow<TelegramAuthState>(TelegramAuthState.Uninitialized)
    override val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    protected val _connectionState =
        MutableStateFlow(TelegramConnectionState.CONNECTING)
    override val connectionState: StateFlow<TelegramConnectionState> =
        _connectionState.asStateFlow()

    protected val _newAudioMessages = MutableSharedFlow<RemoteAudio>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val newAudioMessages: Flow<RemoteAudio> = _newAudioMessages

    @Volatile
    protected var credentials: TelegramCredentials? = null

    @Volatile
    protected var parametersSent: Boolean = false

    /** Publishes an auth state transition; [TelegramAuthState.Ready] also marks connection ready. */
    protected fun publishAuth(state: TelegramAuthState) {
        _authState.value = state
        if (state is TelegramAuthState.Ready) {
            _connectionState.value = TelegramConnectionState.READY
        }
    }

    protected fun publishFailure(message: String) {
        _authState.value = TelegramAuthState.Failed(message)
    }

    protected fun publishClosed() {
        _authState.value = TelegramAuthState.Closed
        parametersSent = false
    }

    protected fun emitNewAudio(audio: RemoteAudio) {
        _newAudioMessages.tryEmit(audio)
    }

    /**
     * Suspends until [TelegramAuthState.Ready] or timeout.
     * Throws [IllegalStateException] when authorization cannot complete.
     */
    protected suspend fun awaitReady() {
        if (_authState.value is TelegramAuthState.Ready) return
        withTimeoutOrNull(TelegramConfig.AUTH_READY_TIMEOUT_MS) {
            authState.first { it is TelegramAuthState.Ready }
        } ?: error("Telegram not authorized")
    }

    protected fun requireCredentials(): TelegramCredentials =
        credentials ?: throw IllegalStateException("Credentials not set")
}
