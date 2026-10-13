package com.extrumz.app.data.telegram

import com.extrumz.app.BuildConfig

/** Mirrors the standalone project's AppConfig for the Telegram feature. */
object TelegramConfig {
    const val DEFAULT_PAGE_LIMIT = 100
    const val ID3_HEADER_MAX_BYTES = 256 * 1024
    const val AUTH_READY_TIMEOUT_MS = 60_000L
    const val MAX_TOP_CHATS = 500
    /**
     * How many GetChat calls [listTrackedCandidateChats] keeps in flight at
     * once. Unbounded it is one coroutine per candidate (up to MAX_TOP_CHATS),
     * which pressure-tests the TDLib JNI bridge all at once on slow phones.
     */
    const val GET_INFO_PARALLEL = 32
    /**
     * LoadChats rounds: TDLib fills its dialog cache in bounded batches and
     * fails with NOT_FOUND only once everything already fits, so the loader
     * loops until that error instead of trusting one call to fill 500.
     */
    const val CHAT_LOAD_ROUNDS = 6
    const val APPLICATION_VERSION = "1.0"

    /**
     * API credentials shipped via BuildConfig (local.properties →
     * build.gradle.kts getters). Never log the hash.
     */
    fun credentials(): TelegramCredentials =
        TelegramCredentials(BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH)
}
