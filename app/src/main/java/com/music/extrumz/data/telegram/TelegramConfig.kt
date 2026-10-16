package com.extrumz.app.data.telegram

import com.extrumz.app.BuildConfig

/** Mirrors the standalone project's AppConfig for the Telegram feature. */
object TelegramConfig {
    const val DEFAULT_PAGE_LIMIT = 100
    const val ID3_HEADER_MAX_BYTES = 256 * 1024
    const val AUTH_READY_TIMEOUT_MS = 60_000L
    const val MAX_TOP_CHATS = 10_000
    /**
     * How many GetChat calls [listTrackedCandidateChats] keeps in flight at
     * once. Unbounded it is one coroutine per candidate (up to MAX_TOP_CHATS),
     * which pressure-tests the TDLib JNI bridge all at once on slow phones.
     */
    /**
     * Chat profile lookups in flight.
     *
     * Back to 32 — see CONTACT_PARALLEL. Spreading this out kept requests in
     * flight into the scan window and made the cooldowns longer, not shorter.
     */
    const val GET_INFO_PARALLEL = 32
    /**
     * LoadChats rounds: TDLib fills its dialog cache in bounded batches and
     * fails with NOT_FOUND only once everything already fits, so the loader
     * loops until that error instead of trusting one call to fill 500.
     */
    const val CHAT_LOAD_ROUNDS = 20

    /**
     * Pages of account-wide audio messages to walk while discovering chats.
     * Each page is one round trip; 20 pages at 100 covers 2000 recent audio
     * messages, which is where every music-bearing chat on this account
     * showed up — beyond that the walk stops paying for itself.
     */
    const val AUDIO_DISCOVERY_PAGES = 20

    /**
     * How long the network-heavy discovery answers (audio search, contacts)
     * are reused before being fetched again. Long enough that a second scan
     * in the same sitting costs nothing, short enough that a track posted
     * moments ago still turns up.
     */
    const val SLOW_SOURCES_TTL_MS = 5 * 60_000L

    /** Page size for the audio discovery walk; TDLib caps SearchMessages at 100. */
    const val AUDIO_DISCOVERY_PAGE_SIZE = 100
    const val APPLICATION_VERSION = "1.0"

    /**
     * API credentials shipped via BuildConfig (local.properties →
     * build.gradle.kts getters). Never log the hash.
     */
    fun credentials(): TelegramCredentials =
        TelegramCredentials(BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH)
}
