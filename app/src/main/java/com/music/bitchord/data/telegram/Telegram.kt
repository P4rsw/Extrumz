package com.music.bitchord.data.telegram

import android.content.Context

/**
 * Process-wide holder for the TDLib client, following the app's
 * `object … init(context)` convention. Constructing the client is cheap and
 * does not start TDLib — the client only boots on the first
 * [MusicTelegramClient.start] (auth entry / source enablement).
 */
object Telegram {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** App context for pieces that need one without a DI graph (MediaStore lookups). */
    val context: Context
        get() = appContext

    val client: MusicTelegramClient by lazy { AndroidMusicTelegramClient(appContext) }

    /** Shared scanner: the sources screen and the source itself must see one progress stream. */
    val scanner: LibraryScanner by lazy { LibraryScanner.create() }
}
