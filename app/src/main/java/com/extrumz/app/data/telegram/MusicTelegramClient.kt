package com.extrumz.app.data.telegram

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

/**
 * Whether the auth state represents a settled unauthenticated state requiring the WelcomeScreen.
 * During [TelegramAuthState.Uninitialized] and [TelegramAuthState.Initializing], the app
 * stays behind the splash screen so authenticated users never experience a flash of WelcomeScreen.
 */
fun shouldShowWelcome(state: TelegramAuthState): Boolean =
    state !is TelegramAuthState.Ready &&
        state !is TelegramAuthState.Uninitialized &&
        state !is TelegramAuthState.Initializing

enum class TelegramConnectionState {
    WAITING_FOR_NETWORK, CONNECTING, UPDATING, READY
}

/**
 * Raised when a stored TDLib file id resolves to a file whose unique id is
 * NOT the one recorded for the track — the stale-id case that would
 * otherwise play a different track while the UI shows the right title.
 */
class StaleFileIdException(fileId: String, expected: String, actual: String) :
    Exception("stale fileId $fileId: resolves to unique $actual, track expects $expected")

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
    val nextFromMessageId: Long,
    /**
     * TDLib's count of audio messages in this chat, known after page 1.
     *
     * The scan never had it: the page was built from messages and
     * nextFromMessageId only, so the client could not tell a chat holding
     * 2,000 tracks from one holding 2. That is why progress had to be
     * counted in chats — a unit where a 15 ms chat and a 30 s chat weigh the
     * same — and why the bar moved in steps. Reading it costs nothing; TDLib
     * already sent it.
     */
    val totalCount: Int = 0,
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

data class TelegramUser(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val username: String?,
    val phoneNumber: String,
    val photoUrl: String?,
    val isPremium: Boolean = false,
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

    /** The authenticated Telegram user's profile info. */
    val currentUser: StateFlow<TelegramUser?>

    /** Re-fetches the current user from Telegram servers. */
    suspend fun fetchCurrentUser(): TelegramUser?

    /** Authorization progress (phone/code/QR/password → [TelegramAuthState.Ready]). */
    val authState: StateFlow<TelegramAuthState>

    /**
     * Routes the connection through [settings], or clears the proxy when it is
     * disabled or incomplete. Called from the settings screen, so a corrected
     * proxy takes effect without restarting the app.
     */
    fun applyProxy(settings: ProxySettings)

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

    /** Restarts the TDLib client session cleanly from the initial parameters. */
    suspend fun restart()

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
     * Server-side search over the user's OWN chat list (searchChatsOnServer).
     * The chat list lives on the server, so a chat TDLib refuses to keep
     * locally (tdlib/td#3673) can still be found by name and matched back to
     * its id. Best-effort: empty when nothing matched.
     */
    suspend fun searchChatOnServer(query: String, limit: Int = 10): List<Long> = emptyList()

    /**
     * Network-backed existence probe for a chat TDLib's offline calls can't
     * see (tdlib/td#3673 — live dialogs that never enter GetChats answer
     * "Chat not found" from the offline GetChat while alive on Telegram):
     * true = reachable (and force-fetched into TDLib's cache on the way, so
     * a scan can follow immediately), false = the server confirms it's gone,
     * null = inconclusive (transient failure) — callers must treat null as
     * "nothing decided" and keep every row.
     */
    suspend fun chatAlive(chatId: Long): Boolean? = getChatInfo(chatId) != null

    /**
     * Diagnostic probe: does TDLib still know this file id? Returns null when
     * GetFile answers (the file stays addressable, so tier-3 can pull it) or
     * the error text otherwise. GetFile reads metadata without downloading.
     */
    suspend fun probeFile(fileId: String): String? = "probeFile not implemented"

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

    /**
     * Tier 3 artwork: an external sleeve lookup (iTunes Search — keyless,
     * one small JSON) for a track whose Telegram message carries NO album
     * cover at all, so the row would otherwise stay placeholder forever.
     * Null when nothing plausible matched. Default null: fakes and tests
     * without network simply keep today's behaviour.
     *
     * [large] is the player's ask: ~1000 px under covers/itl_ instead of the
     * row's 400 px under it_, for a sleeve drawn across the whole hero.
     */
    suspend fun externalCoverUrl(
        chatId: Long,
        messageId: Long,
        title: String?,
        artist: String?,
        large: Boolean = false,
    ): String? = null

    /**
     * Tier 0 artwork: the cover the release itself carries, embedded in
     * the audio file's own header (ID3v2 APIC / FLAC PICTURE). It reads
     * the local copy TDLib already has on disk — no network, no catalogue
     * guess — and persists the picture into covers/ under emb_<unique>,
     * returning a file:// URL. Null when the file is not downloaded yet,
     * carries no picture, or the picture is unreadable; the caller then
     * falls through to the external catalogues exactly as before. Default
     * null: fakes and tests keep today's behaviour.
     *
     * [expectedUniqueId] guards the same stale-id trap as getStreamPath:
     * a stored fileId that now resolves to a different file must not
     * contribute THAT file's art to this track's row.
     */
    suspend fun embeddedTrackCoverUrl(
        fileId: String?,
        expectedUniqueId: String?,
        chatId: Long = 0L,
        messageId: Long = 0L,
    ): String? = null

    /**
     * Candidate chats worth scanning (channels/supergroups): every dialog in
     * the main list plus the archive list, each capped at
     * [TelegramConfig.MAX_TOP_CHATS] — an archived channel must appear here,
     * or the scanner reads its "Chat not found" as "left Telegram" and
     * untracks it.
     */
    suspend fun listTrackedCandidateChats(): List<ChatInfo>
    /** Same candidate id set, without the per-chat GetChat — for cheap polling. */
    suspend fun countCandidateChatIds(): Int

    /**
     * Local filesystem path of a file, downloading it when needed.
     *
     * [expectedUniqueId] is the caller's durable identity for the bytes (the
     * TDLib remote unique id). The numeric [fileId] is session-local: after a
     * TDLib database rebuild the same number can belong to a DIFFERENT file,
     * and GetFile answers with that file instead of failing — which is how
     * track A played track B with no error anywhere. When both sides know the
     * unique id and they disagree, [StaleFileIdException] is thrown rather
     * than serving someone else's audio. Null means "no identity to check".
     */
    suspend fun getStreamPath(fileId: String, offset: Long = 0L, expectedUniqueId: String? = null): String

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
