package com.extrumz.app.data.telegram

import android.content.Context
import android.net.Uri
import com.extrumz.app.BuildConfig
import com.extrumz.app.data.TrackLog
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
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

/**
 * [runCatching] catches [CancellationException] too — a cancelled cover
 * fetch would then come back as `null`, and `Covers.fetchOnce` reads a
 * `null` as "this cover does not exist" and memoizes it for the whole
 * MISS_TTL, blanking a row that was never actually missing. Same trap in
 * `getChatInfo`: a cancelled GetChat reads as "chat has no info" and the
 * candidate chat silently drops out of the pass. Cancellation is a control
 * flow signal, not a failure, so it leaves here untouched; everything else
 * still folds into a failed [Result] exactly as before.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

class AndroidMusicTelegramClient(
    context: Context
) : BaseMusicTelegramClient() {

    private val appContext = context.applicationContext

    private val dataDir: File =
        File(context.filesDir, "extrumz").apply { mkdirs() }

    private var client: Client? = null

    /**
     * Chat-folder ids from TDLib's own UpdateChatFolders — there is no
     * GetChatFolders method, so the update is the only enumeration there
     * is. Empty until the first update arrives; folder loading is
     * insurance (tdlib/td#3673 shows the missing dialogs are not
     * folder-related), so an early scan just skips it.
     */
    @Volatile
    private var chatFolderIds: IntArray = intArrayOf()

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
            is TdApi.UpdateChatFolders -> {
                // TDLib has no way to ASK for the folder list; this update is
                // the enumeration. Captured for ChatListFolder loading in
                // listTrackedCandidateChats.
                chatFolderIds = update.chatFolders.map { it.id }.toIntArray()
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

    /**
     * GetChat is an OFFLINE method ("This is an offline method if the
     * current user is not a bot" — td_api.tl): a chat absent from TDLib's
     * cache answers "Chat not found" while perfectly alive on Telegram.
     * tdlib/td#3673 documents exactly that hole — live dialogs that never
     * enter GetChats/LoadChats are invisible to every offline call, and the
     * issue's working workaround is a network request. Create*Chat with
     * force=false is that door: "Pass true to create the chat WITHOUT a
     * network request" means the default DOES go to the server, pulling the
     * chat into cache where every later GetChat/SearchChatMessages finds it.
     */
    private suspend fun fetchChat(chatId: Long): TdApi.Chat = try {
        send(TdApi.GetChat(chatId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isChatMissing(e)) throw e
        TrackLog.d(TAG, "chat $chatId missing from TDLib cache — forcing a server fetch")
        forceFetchChat(chatId)
    }

    /** Network-backed chat load by id: positive ids are users (private chats), negative are supergroups/channels. */
    private suspend fun forceFetchChat(chatId: Long): TdApi.Chat =
        if (chatId > 0) {
            send(TdApi.CreatePrivateChat(chatId, false))
        } else {
            send(TdApi.CreateSupergroupChat(-1_000_000_000_000L - chatId, false))
        }

    /**
     * "This chat does not exist" versus any OTHER failure — only the former
     * may ever decide a row is gone. A timeout or network blip reads as
     * unknown, never as deleted: an unverifiable probe is not a death
     * certificate.
     */
    private fun isChatMissing(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("not found", ignoreCase = true) ||
            msg.contains("USER_ID_INVALID") ||
            msg.contains("USER_NOT_FOUND") ||
            msg.contains("PEER_ID_INVALID") ||
            msg.contains("CHANNEL_INVALID") ||
            msg.contains("GROUPCHAT_INVALID")
    }

    override suspend fun chatAlive(chatId: Long): Boolean? = try {
        fetchChat(chatId)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.d(TAG, "chatAlive($chatId): ${e::class.simpleName}: ${e.message}")
        if (isChatMissing(e)) false else null
    }

    override suspend fun getChatInfo(chatId: Long): ChatInfo? = runCatchingCancellable {
        val chat = fetchChat(chatId)
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

    override suspend fun searchChatOnServer(query: String, limit: Int): List<Long> =
        runCatchingCancellable<TdApi.Chats> { send(TdApi.SearchChatsOnServer(query, limit)) }
            .map { it.chatIds.toList() }
            .getOrElse {
                TrackLog.w(TAG, "searchChatOnServer('$query') failed: ${it.message}", it)
                emptyList()
            }

    override suspend fun probeFile(fileId: String): String? {
        val id = fileId.toIntOrNull() ?: return "non-int fileId: $fileId"
        var failure: String? = null
        runCatchingCancellable { send(TdApi.GetFile(id)) }
            .onSuccess { return null }
            .onFailure { failure = it.message ?: it::class.simpleName }
        return failure
    }

    /**
     * The same id set [listTrackedCandidateChats] builds, without the per-chat
     * GetChat that dominates its cost: LoadChats plus one GetChats per list.
     *
     * Lets the scanner poll cheaply while TDLib is still pulling dialogs —
     * the full listing would repeat ~200 JNI round trips every poll.
     */
    override suspend fun countCandidateChatIds(): Int {
        awaitReady()
        loadChatList(TdApi.ChatListMain())
        loadChatList(TdApi.ChatListArchive())
        val mainIds = send(TdApi.GetChats(TdApi.ChatListMain(), TelegramConfig.MAX_TOP_CHATS)).chatIds
        val archiveIds = runCatchingCancellable<TdApi.Chats> {
            send(TdApi.GetChats(TdApi.ChatListArchive(), TelegramConfig.MAX_TOP_CHATS))
        }.getOrNull()?.chatIds ?: longArrayOf()
        val seen = mainIds.toHashSet()
        return mainIds.size + archiveIds.count { seen.add(it) }
    }

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
        val mainStop = loadChatList(TdApi.ChatListMain())
        // ChatListArchive is a SEPARATE list: LoadChats(ChatListMain) never
        // touches it, so an archived dialog never enters TDLib's cache — and a
        // chat outside that cache answers GetChat / SearchChatMessages with
        // "Chat not found", which scanOneChat read as "gone from Telegram" and
        // untracked (18 live channels lost that way on 2026-10-07) and which
        // downloadChatPhoto turns into a memoized "no such photo" (the 17 blank
        // shelf avatars). Loading the archive list is the whole fix; its own
        // NOT_FOUND-once-complete failure is the same natural stop signal as
        // the main list's, now through the same every-spelling match (the old
        // NOT_FOUND-only check labeled every COMPLETE archive stop EARLY).
        val archiveStop = loadChatList(TdApi.ChatListArchive())
        // Folder lists are insurance, not the main road: tdlib/td#3673's
        // maintainer confirms the missing dialogs are not folder-related, but
        // loading each folder's list costs one bounded loop, can only ADD
        // candidates (the merge below dedupes against main/archive), and
        // covers any future folder semantics for free. UpdateChatFolders is
        // TDLib's ONLY folder enumeration — before it arrives this stays
        // empty and the whole step is skipped.
        var folderStop = "none"
        val folderListIds = chatFolderIds
        if (folderListIds.isNotEmpty()) {
            // A plain loop, not joinToString(transform): the transform
            // lambda is not a suspend scope and loadChatList suspends.
            val stops = mutableListOf<String>()
            for (folderId in folderListIds) {
                stops += "f$folderId=${loadChatList(TdApi.ChatListFolder(folderId))}"
            }
            folderStop = stops.joinToString(" ")
        }
        TrackLog.d(TAG, "LoadChats main=$mainStop archive=$archiveStop folders=$folderStop")
        val mainIds = send(TdApi.GetChats(TdApi.ChatListMain(), TelegramConfig.MAX_TOP_CHATS)).chatIds
        val archiveIds = runCatchingCancellable<TdApi.Chats> {
            send(TdApi.GetChats(TdApi.ChatListArchive(), TelegramConfig.MAX_TOP_CHATS))
        }.getOrNull()?.chatIds ?: longArrayOf()
        val folderIds = folderListIds.flatMap { folderId ->
            runCatchingCancellable<TdApi.Chats> {
                send(TdApi.GetChats(TdApi.ChatListFolder(folderId), TelegramConfig.MAX_TOP_CHATS))
            }.getOrNull()?.chatIds?.toList() ?: emptyList()
        }.toLongArray()
        // Main first: GetChats answers newest-dialogs-first, so the freshness
        // ranking fullScan launches in survives; archive appends behind it,
        // deduped because each list is read independently. The merged set is
        // NOT re-capped to MAX_TOP_CHATS: taking it would hand every slot back
        // to the main list and reopen exactly the hole fixed above. Each list
        // is already bounded by the GetChats limit, and one GetChat per
        // dialog below stays parallel but bounded — unbounded it is up to
        // MAX_TOP_CHATS concurrent JNI calls, which spikes slow phones.
        // awaitAll preserves input order regardless of which permit each
        // coroutine got, so main-then-archive(-then-folders) is what
        // fullScan receives.
        val seen = mainIds.toHashSet()
        val chatIds = mainIds +
            archiveIds.filter { seen.add(it) } +
            folderIds.filter { seen.add(it) }
        // The counts ARE the diagnosis: main 0 means the dialog sync hasn't
        // finished (LibraryScanner awaits and retries); archive > 0 is the
        // only way to see the fix above actually firing on a real account.
        TrackLog.d(
            TAG,
            "candidates: main=${mainIds.size} archive=${archiveIds.size} " +
                "folders=${folderIds.size} merged=${chatIds.size}",
        )
        val infos = coroutineScope {
            val permits = Semaphore(TelegramConfig.GET_INFO_PARALLEL)
            chatIds
                .map { id -> async { permits.withPermit { getChatInfo(id) } } }
                .awaitAll()
        }
        // Dropping a chat is a decision, not an accident — but it must leave
        // a trace, or "why is chat N never scanned" has no answer in the log.
        infos.forEachIndexed { index, info ->
            if (info == null) {
                TrackLog.w(TAG, "candidate chat ${chatIds[index]} dropped: no chat info")
            }
        }
        // Each list is capped by GetChats itself; re-taking MAX_TOP_CHATS here
        // would truncate the archive half whenever the main list is full —
        // the very failure this function was fixed for.
        return infos.filterNotNull()
    }

    /**
     * One chat list's LoadChats loop: keep paging until TDLib's NOT_FOUND
     * says the whole list is in cache (its error once loaded is the STOP
     * signal, not a failure — matched in every casing, which the archive's
     * old NOT_FOUND-only check got wrong and labeled COMPLETE stops EARLY)
     * or [TelegramConfig.CHAT_LOAD_ROUNDS] runs out. Any other error
     * degrades to "proceed with what is cached", exactly like the old
     * one-call behavior. Returns the stop label for the scan log.
     */
    private suspend fun loadChatList(chatList: TdApi.ChatList): String {
        var round = 0
        while (round < TelegramConfig.CHAT_LOAD_ROUNDS) {
            round++
            val err = runCatchingCancellable {
                send(TdApi.LoadChats(chatList, TelegramConfig.MAX_TOP_CHATS))
            }.exceptionOrNull()
            if (err != null) {
                val msg = err.message.orEmpty()
                return if (msg.contains("not found", ignoreCase = true)) {
                    "complete after $round"
                } else {
                    "EARLY (${msg.ifBlank { err::class.simpleName }}) after $round"
                }
            }
        }
        return "rounds exhausted"
    }

    override suspend fun getAudioByMessage(chatId: Long, messageId: Long): RemoteAudio? {
        awaitReady()
        return runCatchingCancellable {
            val message = send(TdApi.GetMessage(chatId, messageId))
            (message.content as? TdApi.MessageAudio)?.toRemoteAudio(message)
        }.getOrNull()
    }

    override suspend fun downloadChatPhoto(chatId: Long): String? = runCatchingCancellable {
        awaitReady()
        val photo = send(TdApi.GetChat(chatId)).photo ?: return null
        // Big first — shelf cards render at CARD_ART_PX — small as last resort.
        persistCoverFile(photo.big) ?: persistCoverFile(photo.small)
    }.onFailure { e ->
        TrackLog.w(TAG, "downloadChatPhoto($chatId) failed: ${e::class.simpleName}: ${e.message}", e)
    }.getOrNull()

    override suspend fun downloadTrackCover(chatId: Long, messageId: Long): String? = runCatchingCancellable {
        awaitReady()
        val message = send(TdApi.GetMessage(chatId, messageId))
        val content = message.content
        val audio = (content as? TdApi.MessageAudio)?.audio
        if (audio == null) {
            TrackLog.w(TAG, "coverProbe[$chatId/$messageId] content=${content?.javaClass?.simpleName} — not MessageAudio")
            return null
        }
        val thumb = audio.albumCoverThumbnail?.file
        val mini = audio.albumCoverMinithumbnail?.data?.size ?: 0
        TrackLog.w(
            TAG,
            "coverProbe[$chatId/$messageId] title=${audio.title} thumbFile=${thumb?.id ?: "null"} " +
                "thumbExpected=${thumb?.expectedSize ?: 0} miniBytes=$mini"
        )
        // albumCoverThumbnail is the real cover file — albumCoverMinithumbnail
        // (~90px) is what row-level covers already get inline from a scan.
        val url = persistCoverFile(thumb)
        TrackLog.w(TAG, "coverProbe[$chatId/$messageId] persistCoverFile -> $url")
        url
    }.onFailure { e ->
        TrackLog.w(TAG, "downloadTrackCover($chatId, $messageId) failed: ${e::class.simpleName}: ${e.message}", e)
    }.getOrNull()

    /**
     * Tier 3: the message carried no album cover at all, so ask the iTunes
     * Search API for a sleeve by title/performer. Runs inside Covers's
     * fetchOnce gate on Dispatchers.IO; the file lands in covers/ it_<ids>.jpg.
     */
    override suspend fun externalCoverUrl(
        chatId: Long,
        messageId: Long,
        title: String?,
        artist: String?,
        large: Boolean,
    ): String? = ExternalCovers.lookup(
        File(appContext.filesDir, "covers"),
        chatId,
        messageId,
        title,
        artist,
        large,
    )

    override suspend fun embeddedTrackCoverUrl(fileId: String?, expectedUniqueId: String?): String? {
        val id = fileId?.toIntOrNull() ?: return null
        awaitReady()
        // GetFile answers from TDLib's local state and does NOT pull the
        // file: this runs even when only the first streamed chunks exist,
        // and costs no network when the file is already complete.
        val file = runCatchingCancellable { send(TdApi.GetFile(id)) }
            .getOrElse { return null }
        // The stale-id guard: an id that now resolves to a different file
        // must not lend its artwork to this track — same rule as playback.
        val unique = file.remote?.uniqueId
        if (!expectedUniqueId.isNullOrBlank() && !unique.isNullOrBlank() && unique != expectedUniqueId) {
            TrackLog.w(TAG, "Embedded art: stale fileId $fileId (unique $unique, expected $expectedUniqueId)")
            return null
        }
        val path = file.local?.path
        if (path.isNullOrBlank()) return null
        val art = runCatchingCancellable { EmbeddedArt.largest(File(path)) }
            .getOrElse {
                TrackLog.w(TAG, "Embedded art read failed for fileId $fileId: ${it.message}")
                return null
            } ?: return null
        // Keyed by the remote unique id (session-stable, like hq_ covers),
        // so one play writes it and every later session finds it already
        // on disk — the parse never repeats.
        val key = unique?.takeIf { it.isNotBlank() } ?: fileId
        val ext = if (art.mimeType == "image/png") "png" else if (art.mimeType == "image/webp") "webp" else "jpg"
        val target = File(File(appContext.filesDir, "covers").apply { mkdirs() }, "emb_$key.$ext")
        if (!target.exists()) {
            runCatchingCancellable { target.writeBytes(art.bytes) }
                .getOrElse {
                    TrackLog.w(TAG, "Embedded art write failed for $fileId: ${it.message}")
                    return null
                }
        }
        TrackLog.d(TAG, "Embedded art for fileId $fileId -> ${target.name} (${art.bytes.size} bytes, ${art.mimeType})")
        return Uri.fromFile(target).toString()
    }

    /**
     * Fetches one artwork file, copies it into covers/ under a key that is
     * stable across sessions (the remote's unique id, not this session's file
     * id), and hands back a `file://` URL. A file already in the cache returns
     * with no network at all; oversized artwork is refused outright so an
     * open-ended scroll can never turn into an open-ended download.
     */
    private suspend fun persistCoverFile(file: TdApi.File?): String? {
        if (file == null || file.id <= 0) return null
        if (file.expectedSize > MAX_COVER_BYTES) return null
        val key = file.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: file.id.toString()
        val target = File(File(appContext.filesDir, "covers").apply { mkdirs() }, "hq_$key.jpg")
        if (!target.exists()) {
            val local = send(TdApi.DownloadFile(file.id, 1, 0, 0, true))
            val path = local.local?.path
            if (local.local?.isDownloadingCompleted != true || path.isNullOrBlank()) return null
            val bytes = File(path).readBytes()
            if (bytes.isEmpty()) return null
            target.writeBytes(bytes)
        }
        return Uri.fromFile(target).toString()
    }

    /** Artwork bigger than this is not worth its bytes on a phone. */
    private val MAX_COVER_BYTES = 5_000_000L

    override suspend fun getStreamPath(
        fileId: String,
        offset: Long,
        expectedUniqueId: String?,
    ): String {
        val id = fileId.toIntOrNull() ?: error("Invalid file id")
        awaitReady()
        val file = send(TdApi.GetFile(id))
        verifyUniqueId(file, expectedUniqueId, fileId)
        val localPath = file.local?.path
        if (file.local?.isDownloadingCompleted == true && !localPath.isNullOrBlank()) {
            return localPath
        }
        send(TdApi.DownloadFile(id, 1, offset, 0, true))
        return send(TdApi.GetFile(id)).local?.path?.takeIf { it.isNotBlank() }
            ?: error("File not available: $fileId")
    }

    /**
     * TDLib file ids are session-local and get REISSUED to other files when
     * its database is rebuilt; GetFile on such an id succeeds instead of
     * failing, silently returning someone else's bytes — that is how track A
     * played track B with no error anywhere. The remote unique id is the
     * durable identity, so when the caller knows the id it recorded and the
     * file disagrees, refuse rather than serve the wrong track. Blank on
     * either side means there is nothing to compare and the old behavior
     * stands (the caller's message fetch remains the fallback).
     */
    private fun verifyUniqueId(file: TdApi.File, expected: String?, fileId: String) {
        if (expected.isNullOrBlank()) return
        val actual = file.remote?.uniqueId
        if (actual.isNullOrBlank()) return
        if (actual != expected) {
            throw StaleFileIdException(fileId, expected, actual)
        }
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
            TdApi.CreateChatInviteLink(chatId, "Extrumz", 0, 0, false)
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
