package com.music.bitchord.data.telegram

import com.music.bitchord.BuildConfig

/** Mirrors the standalone project's AppConfig for the Telegram feature. */
object TelegramConfig {
    const val DEFAULT_PAGE_LIMIT = 100
    const val ID3_HEADER_MAX_BYTES = 256 * 1024
    const val AUTH_READY_TIMEOUT_MS = 60_000L
    const val MAX_TOP_CHATS = 500
    const val APPLICATION_VERSION = "1.0"

    /**
     * API credentials shipped via BuildConfig (local.properties →
     * build.gradle.kts getters). Never log the hash.
     */
    fun credentials(): TelegramCredentials =
        TelegramCredentials(BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH)
}
