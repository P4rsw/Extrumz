package com.extrumz.app.data.telegram

import kotlinx.coroutines.flow.MutableStateFlow

/** UI-level state shared between the songs and channels tabs. */
object TelegramUi {
    /** When set, the songs tab only shows tracks indexed from this chat. */
    val selectedChatId = MutableStateFlow<Long?>(null)
}
