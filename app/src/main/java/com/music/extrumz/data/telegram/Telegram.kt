package com.extrumz.app.data.telegram

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
        // Before the client can connect: the proxy has to be readable by the
        // time TDLib is told to start, or the first attempt goes out direct
        // and fails on a network that needs one.
        ProxyStore.init(appContext)
    }

    /** App context for pieces that need one without a DI graph (MediaStore lookups). */
    val context: Context
        get() = appContext

    val client: MusicTelegramClient by lazy { AndroidMusicTelegramClient(appContext) }

    /** Shared scanner: the sources screen and the source itself must see one progress stream. */
    val scanner: LibraryScanner by lazy { LibraryScanner.create() }
}
