package com.music.bitchord.data.telegram

import android.content.Context
import android.net.Uri
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "TelegramClient"

class AndroidMusicTelegramClient(
    context: Context
) : BaseMusicTelegramClient() {

    private val appContext = context.applicationContext

    private val dataDir: File =
        File(context.filesDir, "telemusic").apply { mkdirs() }

    private var client: Client? = null

    override suspend fun start(credentials: TelegramCredentials) {
        this.credentials = credentials
        if (client == null) {
            publishAuth(TelegramAuthState.Initializing)
            parametersSent = false
            client = Client.create(
                { update -> handleUpdate(update) },
                { throwable ->
                    publishFailure(throwable.message ?: "TDLib error")
                },
                { throwable ->
                    publishFailure(throwable.message ?: "TDLib exception")
                }
            )
        }
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuth(update.authorizationState)
            is TdApi.UpdateConnectionState -> {
                _connectionState.value = when (update.state) {
                    is TdApi.ConnectionStateWaitingForNetwork ->
                        TelegramConnectionState.WAITING_FOR_NETWORK
                    is TdApi.ConnectionStateConnecting,
                    is TdApi.ConnectionStateConnectingToProxy ->
                        TelegramConnectionState.CONNECTING
                    is TdApi.ConnectionStateUpdating ->
                        TelegramConnectionState.UPDATING
                    else -> TelegramConnectionState.READY
                }
            }
            is TdApi.UpdateNewMessage -> {
                val content = update.message.content
                if (content is TdApi.MessageAudio) {
                    content.toRemoteAudio(update.message)?.let { emitNewAudio(it) }
                }
            }
            else -> Unit
        }
    }

    private fun handleAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val creds = credentials ?: return
                if (!parametersSent) {
                    parametersSent = true
                    val params = TdApi.SetTdlibParameters()
                    params.useTestDc = false
                    params.databaseDirectory = File(dataDir, "db").absolutePath
                    params.filesDirectory = File(dataDir, "files").absolutePath
                    params.useFileDatabase = true
                    params.useChatInfoDatabase = true
                    params.useMessageDatabase = true
                    params.useSecretChats = false
                    params.apiId = creds.apiId
                    params.apiHash = creds.apiHash
                    params.systemLanguageCode = java.util.Locale.getDefault().language
                    params.deviceModel =
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
                    params.applicationVersion = appVersion()
                    client?.send(params, null)
                    configureProxyIfConfigured()
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                publishAuth(TelegramAuthState.WaitingForPhoneNumber)
            is TdApi.AuthorizationStateWaitCode ->
                publishAuth(TelegramAuthState.WaitingForCode(""))
            is TdApi.AuthorizationStateWaitPassword ->
                publishAuth(TelegramAuthState.WaitingForPassword(state.passwordHint))
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                publishAuth(TelegramAuthState.WaitingForQrScan(state.link))
            is TdApi.AuthorizationStateReady -> publishAuth(TelegramAuthState.Ready)
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> publishClosed()
            is TdApi.AuthorizationStateWaitRegistration ->
                publishFailure("Registration required — use an official Telegram client")
            else -> Unit
        }
    }

    /**
     * Routes TDLib through the local SOCKS5 proxy from local.properties when
     * configured (BuildConfig.PROXY_HOST/PROXY_PORT). Functions sent before
     * SetTdlibParameters completes are queued by TDLib, so the proxy is in
     * place for the first connection attempt whenever possible. Blank host
     * (dev/prod default) keeps the direct connection.
     */
    private fun configureProxyIfConfigured() {
        val host = BuildConfig.PROXY_HOST
        val port = BuildConfig.PROXY_PORT
        if (host.isBlank() || port <= 0) return
        runCatching {
            client?.send(TdApi.AddProxy(host, port, true, TdApi.ProxyTypeSocks5())) { result ->
                if (result is TdApi.Proxy) {
                    client?.send(TdApi.EnableProxy(result.id), null)
                }
            }
        }
    }

    private suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val c = client ?: error("Client not started")
        return suspendCancellableCoroutine { cont ->
            c.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(
                        TelegramClientException(
                            result.message ?: "TDLib call failed",
                            result.code
                        )
                    )
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }
    }

    override suspend fun submitPhoneNumber(phoneNumber: String) {
        send(
            TdApi.SetAuthenticationPhoneNumber(
                phoneNumber,
                TdApi.PhoneNumberAuthenticationSettings()
            )
        )
    }

    override suspend fun submitCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    override suspend fun submitPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun requestQrCodeAuthentication() {
        send(TdApi.RequestQrCodeAuthentication(LongArray(0)))
    }

    override suspend fun confirmLoginLink(link: String) {
        send(TdApi.ConfirmQrCodeAuthentication(link))
    }

    override suspend fun logout() {
        send(TdApi.LogOut())
        parametersSent = false
    }

    override suspend fun searchAllAudio(query: String, limit: Int): List<RemoteAudio> {
        awaitReady()
        val found = send(
            TdApi.SearchMessages(
                TdApi.ChatListMain(),
                query,
                "",
                limit,
                TdApi.SearchMessagesFilterAudio(),
                null,
                0,
                0
            )
        )
        return found.messages.mapNotNull { m ->
            (m.content as? TdApi.MessageAudio)?.toRemoteAudio(m)
        }
    }

    override suspend fun getChatAudio(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): RemoteAudioPage {
        awaitReady()
        if (fromMessageId == 0L) {
            runCatching { send<TdApi.Ok>(TdApi.OpenChat(chatId)) }
        }
        val found = send(
            TdApi.SearchChatMessages(
                chatId,
                null,
                "",
                null,
                fromMessageId,
                0,
                limit,
                TdApi.SearchMessagesFilterAudio()
            )
        )
        return RemoteAudioPage(
            audios = found.messages.mapNotNull { m ->
                (m.content as? TdApi.MessageAudio)?.toRemoteAudio(m)
            },
            nextFromMessageId = found.nextFromMessageId
        )
    }

    /**
     * The chat avatar as a `file://` URL from Telegram's INLINE photo
     * minithumbnail — no download round-trip, so scanning every tracked
     * chat costs zero extra RPC. Photos without inline bytes come back null
     * and the shelf keeps its blue mesh.
     */
    private fun chatPhotoUrl(photo: TdApi.ChatPhotoInfo?): String? {
        val data = photo?.minithumbnail?.data
        val fileId = photo?.small?.id ?: 0
        if (data == null || data.isEmpty() || fileId <= 0) return null
        return persistInlineCover(fileId, data)
    }

    override suspend fun getChatInfo(chatId: Long): ChatInfo? = runCatching {
        val chat = send(TdApi.GetChat(chatId))
        val type = chat.type
        // Usernames only ever build t.me links and nothing reads this field
        // (grep finds the write-only entity column); resolving them cost an
        // extra RPC per supergroup on top of GetChat, so they come back lazily
        // if a UI actually needs one.
        ChatInfo(
            chatId = chat.id,
            title = chat.title,
            username = null,
            isChannel = type is TdApi.ChatTypeSupergroup && type.isChannel,
            isGroup = type is TdApi.ChatTypeBasicGroup ||
                (type is TdApi.ChatTypeSupergroup && !type.isChannel),
            photoUrl = chatPhotoUrl(chat.photo)
        )
    }.onFailure { e ->
        // A null here drops the chat from every future scan silently — log the
        // real exception (with chatId) here, where the cause still is.
        TrackLog.w(TAG, "getChatInfo($chatId) failed: ${e::class.simpleName}: ${e.message}", e)
    }.getOrNull()

    override suspend fun listTrackedCandidateChats(): List<ChatInfo> {
        awaitReady()
        // GetChats only answers with dialogs TDLib has already cached; LoadChats
        // pulls them into that cache page by page, and a single call only fills
        // ONE bounded batch — on a fresh account most of MAX_TOP_CHATS would
        // still be missing. Loop until TDLib says it's all there (its error once
        // the list already fits is the stop signal, not a failure) or the answers
        // stop growing. Any other error degrades to exactly the old one-call
        // behavior: proceed with whatever is cached.
        // LoadChats answers Ok per batch (the result carries no list), and
        // fails with NOT_FOUND only once the whole list is in cache — that
        // failure is the natural stop signal. Any other error degrades to
        // exactly the old single-call behavior: proceed with what is cached.
        var round = 0
        while (round < TelegramConfig.CHAT_LOAD_ROUNDS) {
            round++
            val loaded = runCatching {
                send(TdApi.LoadChats(TdApi.ChatListMain(), TelegramConfig.MAX_TOP_CHATS))
            }.isSuccess
            if (!loaded) break
        }
        val slice = send(TdApi.GetChats(TdApi.ChatListMain(), TelegramConfig.MAX_TOP_CHATS))
        // One GetChat per dialog, fetched in parallel but bounded: unbounded
        // it is up to MAX_TOP_CHATS concurrent JNI calls, which spikes slow
        // phones. awaitAll preserves the input order regardless of which
        // permit each coroutine got, and GetChats answers newest-first, so the
        // freshness ranking fullScan launches in survives intact.
        val infos = coroutineScope {
            val permits = Semaphore(TelegramConfig.GET_INFO_PARALLEL)
            slice.chatIds
                .map { id -> async { permits.withPermit { getChatInfo(id) } } }
                .awaitAll()
        }
        // Dropping a chat is a decision, not an accident — but it must leave
        // a trace, or "why is chat N never scanned" has no answer in the log.
        infos.forEachIndexed { index, info ->
            if (info == null) {
                TrackLog.w(TAG, "candidate chat ${slice.chatIds[index]} dropped: no chat info")
            }
        }
        return infos.filterNotNull().take(TelegramConfig.MAX_TOP_CHATS)
    }

    override suspend fun getAudioByMessage(chatId: Long, messageId: Long): RemoteAudio? {
        awaitReady()
        return runCatching {
            val message = send(TdApi.GetMessage(chatId, messageId))
            (message.content as? TdApi.MessageAudio)?.toRemoteAudio(message)
        }.getOrNull()
    }

    override suspend fun getStreamPath(fileId: String, offset: Long): String {
        val id = fileId.toIntOrNull() ?: error("Invalid file id")
        awaitReady()
        val file = send(TdApi.GetFile(id))
        val localPath = file.local?.path
        if (file.local?.isDownloadingCompleted == true && !localPath.isNullOrBlank()) {
            return localPath
        }
        send(TdApi.DownloadFile(id, 1, offset, 0, true))
        return send(TdApi.GetFile(id)).local?.path?.takeIf { it.isNotBlank() }
            ?: error("File not available: $fileId")
    }

    override suspend fun downloadFile(fileId: String, synchronous: Boolean): String =
        getStreamPath(fileId, 0L)

    override suspend fun downloadPartial(fileId: String, offset: Long, limit: Long): String {
        val id = fileId.toIntOrNull() ?: error("Invalid file id")
        awaitReady()
        val file = send(TdApi.DownloadFile(id, 1, offset, limit, false))
        return file.local?.path?.takeIf { it.isNotBlank() }
            ?: error("Partial file not available: $fileId")
    }

    override suspend fun createPlaylistChannel(title: String, description: String): Long {
        awaitReady()
        val channel = send(
            TdApi.CreateNewSupergroupChat(
                title,
                false,
                true,
                description,
                null,
                0,
                false
            )
        )
        return channel.id
    }

    override suspend fun addToPlaylistChannel(
        playlistChatId: Long,
        chatId: Long,
        messageId: Long
    ): Long {
        awaitReady()
        val forwarded = send(
            TdApi.ForwardMessages(
                playlistChatId,
                null,
                chatId,
                longArrayOf(messageId),
                TdApi.MessageSendOptions(),
                false,
                false
            )
        )
        return forwarded.messages.firstOrNull()?.id ?: error("Forward failed")
    }

    override suspend fun createInviteLink(chatId: Long): String {
        awaitReady()
        val link = send(
            TdApi.CreateChatInviteLink(chatId, "TeleMusic", 0, 0, false)
        )
        return link.inviteLink
    }

    override suspend fun addChatAdmin(chatId: Long, userChatId: Long): Boolean = runCatching {
        awaitReady()
        val rights = TdApi.ChatAdministratorRights()
        rights.canInviteUsers = true
        rights.canPostMessages = true
        rights.canEditMessages = true
        rights.canChangeInfo = true
        // tdlib 1.8.56 signature: (customTitle, canBeEdited, rights) — canBeEdited=false matches Desktop.
        val status = TdApi.ChatMemberStatusAdministrator("", false, rights)
        send(
            TdApi.SetChatMemberStatus(
                chatId,
                TdApi.MessageSenderUser(userChatId),
                status
            )
        )
        true
    }.getOrDefault(false)

    override suspend fun searchPublicChannels(query: String, limit: Int): List<ChatInfo> {
        awaitReady()
        if (query.isBlank()) return emptyList()
        val chats = send(TdApi.SearchPublicChats(query))
        return chats.chatIds.take(limit).mapNotNull { id ->
            getChatInfo(id)?.takeIf { it.isChannel || it.isGroup }
        }
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            ?: TelegramConfig.APPLICATION_VERSION
    }.getOrDefault(TelegramConfig.APPLICATION_VERSION)

    /**
     * Writes Telegram's inline album minithumbnail to the covers dir and
     * returns a `file://` URL for it. Covers are keyed by TDLib's file id:
     * the artwork shared by several messages is written once, and an
     * existing file is never rewritten.
     */
    private fun persistInlineCover(fileId: Int, data: ByteArray?): String? {
        if (data == null || data.isEmpty()) return null
        return runCatching {
            val dir = File(appContext.filesDir, "covers").apply { mkdirs() }
            val cover = File(dir, "$fileId.jpg")
            if (!cover.exists()) cover.writeBytes(data)
            Uri.fromFile(cover).toString()
        }.getOrNull()
    }

    private fun TdApi.MessageAudio.toRemoteAudio(message: TdApi.Message): RemoteAudio? {
        val audio = this.audio ?: return null
        val file = audio.audio ?: return null
        return RemoteAudio(
            chatId = message.chatId,
            messageId = message.id,
            remoteFileId = file.id.toString(),
            uniqueFileId = file.remote?.uniqueId ?: file.id.toString(),
            title = audio.title?.takeIf { it.isNotBlank() },
            performer = audio.performer?.takeIf { it.isNotBlank() },
            fileName = audio.fileName?.takeIf { it.isNotBlank() },
            mimeType = audio.mimeType ?: "audio/mpeg",
            durationSeconds = audio.duration,
            sizeBytes = file.expectedSize.takeIf { it > 0 } ?: file.size,
            caption = this.caption?.text.orEmpty(),
            dateSeconds = message.date.toLong(),
            mediaAlbumId = message.mediaAlbumId.takeIf { it != 0L },
            coverUrl = persistInlineCover(file.id, audio.albumCoverMinithumbnail?.data)
        )
    }
}
