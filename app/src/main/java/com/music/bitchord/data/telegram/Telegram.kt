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

    val client: MusicTelegramClient by lazy { AndroidMusicTelegramClient(appContext) }
}
