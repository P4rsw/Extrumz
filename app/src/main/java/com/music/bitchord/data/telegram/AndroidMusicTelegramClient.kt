package com.music.bitchord.data.telegram

import android.content.Context
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    override suspend fun getChatInfo(chatId: Long): ChatInfo? = runCatching {
        val chat = send(TdApi.GetChat(chatId))
        val type = chat.type
        val username = if (type is TdApi.ChatTypeSupergroup) {
            runCatching {
                send(TdApi.GetSupergroup(type.supergroupId)).usernames
                    ?.activeUsernames
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        } else {
            null
        }
        ChatInfo(
            chatId = chat.id,
            title = chat.title,
            username = username,
            isChannel = type is TdApi.ChatTypeSupergroup && type.isChannel,
            isGroup = type is TdApi.ChatTypeBasicGroup ||
                (type is TdApi.ChatTypeSupergroup && !type.isChannel)
        )
    }.getOrNull()

    override suspend fun listTrackedCandidateChats(): List<ChatInfo> {
        awaitReady()
        val result = mutableListOf<ChatInfo>()
        val slice = send(TdApi.GetChats(TdApi.ChatListMain(), TelegramConfig.MAX_TOP_CHATS))
        for (id in slice.chatIds) {
            getChatInfo(id)?.let { result.add(it) }
            if (result.size >= TelegramConfig.MAX_TOP_CHATS) break
        }
        return result
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
            durationSeconds = audio.duration ?: 0,
            sizeBytes = file.expectedSize.takeIf { it > 0 } ?: file.size,
            caption = this.caption?.text.orEmpty(),
            dateSeconds = message.date.toLong(),
            mediaAlbumId = message.mediaAlbumId.takeIf { it != 0L }
        )
    }
}
