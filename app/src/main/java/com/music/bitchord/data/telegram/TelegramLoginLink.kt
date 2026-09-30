package com.music.bitchord.data.telegram

import android.content.Intent

/**
 * Consumes `tg://login?token=…` deep links — what the official clients' QR
 * confirmation emits. The token is handed to [TelegramAuthController], which
 * holds it until TDLib is in a state that can accept it (the confirmation is
 * only valid once TDLib's own parameters have executed); phone entry stays
 * the fallback if the token is rejected. The data is cleared so a rotation
 * or a redelivered intent never replays a spent token.
 */
object TelegramLoginLink {
    fun consume(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "tg" || uri.host != "login") return
        val token = uri.getQueryParameter("token") ?: return
        intent.data = null
        TelegramAuthController.onLoginLink("tg://login?token=$token")
    }
}
