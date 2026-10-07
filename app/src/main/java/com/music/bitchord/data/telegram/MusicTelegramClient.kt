package com.music.bitchord.data.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class TelegramCredentials(
    val apiId: Int,
    val apiHash: String
) {
    override fun toString(): String = "TelegramCredentials(apiId=***, apiHash=***)"
}

sealed interface TelegramAuthState {
    data object Uninitialized : TelegramAuthState
    data object Initializing : TelegramAuthState
    data object WaitingForPhoneNumber : TelegramAuthState
    data class WaitingForCode(val phoneNumber: String) : TelegramAuthState
    data class WaitingForQrScan(val link: String) : TelegramAuthState
    data class WaitingForPassword(val passwordHint: String?) : TelegramAuthState
    data class Failed(val message: String) : TelegramAuthState
    data object Ready : TelegramAuthState
    data object Closed : TelegramAuthState
}

enum class TelegramConnectionState {
    WAITING_FOR_NETWORK, CONNECTING, UPDATING, READY
}

/** Audio message normalized without TDLib types crossing the boundary. */
data class RemoteAudio(
    val chatId: Long,
    val messageId: Long,
    val remoteFileId: String,
    val uniqueFileId: String,
    val title: String?,
    val performer: String?,
    val fileName: String?,
    val mimeType: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val caption: String,
    val dateSeconds: Long,
    val mediaAlbumId: Long?,
    /** Local `file://` cover written from Telegram's inline album minithumbnail, null when the message has none. */
    val coverUrl: String? = null
)

data class RemoteAudioPage(
    val audios: List<RemoteAudio>,
    val nextFromMessageId: Long
)

data class ChatInfo(
    val chatId: Long,
    val title: String,
    val username: String?,
    val isChannel: Boolean,
    val isGroup: Boolean,
    /** `file://` avatar written from Telegram's inline photo minithumbnail, null when the chat has none. */
    val photoUrl: String? = null
)

class TelegramClientException(
    message: String,
    val code: Int
) : Exception(message)

/**
 * Telegram client backed by TDLib (org.drinkless.tdlib).
 *
 * Implementations live in [BaseMusicTelegramClient] subclasses and expose
 * auth/connection state as [StateFlow]s for UI observation.
 */
interface MusicTelegramClient {

    /** Authorization progress (phone/code/QR/password → [TelegramAuthState.Ready]). */
    val authState: StateFlow<TelegramAuthState>

    /** TDLib connection quality; independent from authorization. */
    val connectionState: StateFlow<TelegramConnectionState>

    /** Hot stream of newly received audio messages (drops oldest when slow). */
    val newAudioMessages: Flow<RemoteAudio>

    /** Initializes the TDLib client and begins the auth flow. */
    suspend fun start(credentials: TelegramCredentials)

    /** Submits the phone number for code/QR delivery. */
    suspend fun submitPhoneNumber(phoneNumber: String)

    /** Confirms the login code received via SMS/Telegram. */
    suspend fun submitCode(code: String)

    /** Confirms the 2FA cloud password. */
    suspend fun submitPassword(password: String)

    /** Switches the auth flow to QR-code login on another device. */
    suspend fun requestQrCodeAuthentication()

    /** Completes login from a `tg://login?token=` deep link, when TDLib accepts it. */
    suspend fun confirmLoginLink(link: String)

    /** Logs out of the current session and clears parameter state. */
    suspend fun logout()

    /** Global message search for audio files. */
    suspend fun searchAllAudio(query: String = "", limit: Int = TelegramConfig.DEFAULT_PAGE_LIMIT): List<RemoteAudio>

    /** One page of audio messages in a chat; [fromMessageId] is the paging cursor. */
    suspend fun getChatAudio(
        chatId: Long,
        fromMessageId: Long = 0L,
        limit: Int = TelegramConfig.DEFAULT_PAGE_LIMIT
    ): RemoteAudioPage

    /** Chat metadata (title/username/type), or null when unavailable. */
    suspend fun getChatInfo(chatId: Long): ChatInfo?

    /**
     * Re-reads one message's audio — the way to refresh a [RemoteAudio] whose
     * file id TDLib has since invalidated ("Not Found" on download). Null when
     * the message is gone or is not an audio message.
     */
    suspend fun getAudioByMessage(chatId: Long, messageId: Long): RemoteAudio?

    /**
     * The chat's full-size photo, copied into the app's covers cache and
     * returned as a `file://` URL — the sharp source behind a shelf card,
     * which renders at CARD_ART_PX, far above the inline minithumbnail's
     * ~90px. Defaults to null so fakes and unauthenticated clients need no
     * artwork behaviour; [Covers] only calls it on a live session and only
     * once per unique photo.
     */
    suspend fun downloadChatPhoto(chatId: Long): String? = null

    /**
     * One track's full-size album cover, same caching contract as
     * [downloadChatPhoto]. Null when the message is gone or carries no cover.
     */
    suspend fun downloadTrackCover(chatId: Long, messageId: Long): String? = null

    /** Candidate chats worth scanning (channels/supergroups), capped at [TelegramConfig.MAX_TOP_CHATS]. */
    suspend fun listTrackedCandidateChats(): List<ChatInfo>

    /** Local filesystem path of a file, downloading it when needed. */
    suspend fun getStreamPath(fileId: String, offset: Long = 0L): String

    /** Ensures the full file is downloaded and returns its local path. */
    suspend fun downloadFile(fileId: String, synchronous: Boolean = false): String

    /** Downloads only a byte range (ID3 header) for cheap metadata parsing. */
    suspend fun downloadPartial(fileId: String, offset: Long = 0L, limit: Long = TelegramConfig.ID3_HEADER_MAX_BYTES.toLong()): String = downloadFile(fileId, true)

    /** Creates a private supergroup used as a playlist channel. */
    suspend fun createPlaylistChannel(title: String, description: String): Long

    /** Forwards an audio message into the playlist channel; returns new message id. */
    suspend fun addToPlaylistChannel(
        playlistChatId: Long,
        chatId: Long,
        messageId: Long
    ): Long

    /** Creates an invite link for the playlist channel. */
    suspend fun createInviteLink(chatId: Long): String

    /** Grants admin rights so the bot/client can post; false when it fails. */
    suspend fun addChatAdmin(chatId: Long, userChatId: Long): Boolean

    /** Public channel/group search by query. */
    suspend fun searchPublicChannels(query: String, limit: Int = 20): List<ChatInfo>
}
