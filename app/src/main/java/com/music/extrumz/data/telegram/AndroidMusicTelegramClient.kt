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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TelegramClient"
/** Contacts resolved concurrently; capped because the server flood-controls this. */
/**
 * Contacts resolved concurrently.
 *
 * Back to 8. Lowering it to 2 was a mistake with a measurable cost: instead of
 * a short burst that finishes, the lookups became a sustained drip that was
 * still running when the scan workers started, keeping pressure on an already
 * exhausted rate-limit bucket. Stalls went from ~135s to ~208s. Finishing the
 * burst EARLY is what matters, not making it small.
 */
private const val CONTACT_PARALLEL = 8

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

    /** For the few calls that must outlive the caller, like a network reset. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            // Native TDLib log at verbosity 2. This is the only place that
            // says WHY a query took 75 seconds: NetQueryDelayer swallows a
            // 420 FLOOD_WAIT internally and retries, so the Java side sees
            // only a slow call and never the reason. Grep TDLibNative.
            runCatching {
                Client.setLogMessageHandler(2) { _, message ->
                    com.extrumz.app.data.TrackLog.w("TDLibNative", message)
                }
            }
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
                    // Before the parameters, not after. TDLib queues what
                    // arrives early, and sending it first means the proxy is
                    // already in place when the first connection is attempted
                    // -- on a filtered network the direct attempt does not just
                    // fail, it spends the backoff budget before the proxy is
                    // ever consulted.
                    configureProxyIfConfigured()
                    client?.send(params) { result ->
                        if (result is TdApi.Error) {
                            com.extrumz.app.data.TrackLog.e("TDLib", "SetTdlibParameters error: ${result.code} - ${result.message}")
                            val msg = result.message.orEmpty()
                            if (msg.contains("database", ignoreCase = true) ||
                                msg.contains("lock", ignoreCase = true) ||
                                msg.contains("corrupt", ignoreCase = true) ||
                                msg.contains("disk", ignoreCase = true)
                            ) {
                                com.extrumz.app.data.TrackLog.w("TDLib", "TDLib DB locked/corrupt. Auto-clearing db folder to recover...")
                                appScope.launch {
                                    runCatching { File(dataDir, "db").deleteRecursively() }
                                    restart()
                                }
                            } else {
                                publishFailure(result.message ?: "TDLib error")
                            }
                        }
                    }
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
            is TdApi.AuthorizationStateReady -> {
                publishAuth(TelegramAuthState.Ready)
                appScope.launch { runCatching { fetchCurrentUser() } }
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing -> {
                _currentUser.value = null
            }
            is TdApi.AuthorizationStateClosed -> {
                _currentUser.value = null
                client = null
                parametersSent = false
                publishClosed()
            }
            is TdApi.AuthorizationStateWaitRegistration ->
                publishFailure("Registration required — use an official Telegram client")
            else -> Unit
        }
    }

    /**
     * Routes TDLib through the user's proxy.
     *
     * This used to read BuildConfig.PROXY_HOST/PORT — a value baked in from
     * local.properties at build time — and hardcode SOCKS5. Both were wrong
     * for the people this app is for. A user in Iran reaches nothing without a
     * proxy, so a proxy they cannot enter is a proxy they do not have; and
     * SOCKS5 is what produced the flood the scan died on. The settings now
     * live in [ProxyStore], pasted straight from a t.me link, and MTProto is
     * among the kinds that can be saved.
     *
     * Functions sent before SetTdlibParameters completes are queued by TDLib,
     * so the proxy is in place for the first connection attempt whenever
     * possible. Nothing configured keeps the direct connection.
     */
    private fun configureProxyIfConfigured() {
        val settings = runCatching { ProxyStore.current }.getOrNull() ?: return
        applyProxy(settings)
    }

    /**
     * Applies [settings] to the running client, or clears the proxy when they
     * are not usable. Safe to call while connected — this is the entry point
     * the settings screen uses, so a corrected proxy takes effect without a
     * restart.
     */
    override fun applyProxy(settings: ProxySettings) {
        // No network reset on this path. It runs while TDLib is still starting,
        // before anything is connected, so there is nothing to disturb — and
        // dropping the network here kills the first connection instead, which
        // is what a launch looked like: connected, then refused, then nothing.
        applyProxy(settings, resetNetwork = false)
    }

    /**
     * Applies [settings], and optionally asks TDLib to re-evaluate the network
     * straight away.
     *
     * [resetNetwork] is true only when the user has just changed something, and
     * it exists for one case: changing the proxy does not disturb a connection
     * attempt that is already backing off, and the device log showed what that
     * costs — a config fetch fails and the next attempt is 151 seconds away, so
     * a corrected proxy did nothing visible for two and a half minutes.
     *
     * Doing it on the startup path was wrong: nothing is connected yet, so the
     * reset has no flood to break, and it tears down the first connection
     * instead. Dialogs died with two requests in flight.
     */
    fun applyProxy(settings: ProxySettings, resetNetwork: Boolean) {
        val c = client ?: return
        if (!settings.enabled || !settings.isUsable) {
            // Never silently. An unreadable secret used to land here and the
            // proxy was simply switched off: the app then connected directly,
            // which on a filtered network reaches nothing, and nothing in the
            // log or the UI said why. Three different links "did not work"
            // because of it.
            if (settings.enabled && settings.kind == ProxyKind.MTProto) {
                TrackLog.w(
                    TAG,
                    "proxy disabled: secret unreadable (${settings.secret.length} chars) " +
                        "- not a supported MTProto secret shape",
                )
            }
            runCatching { c.send(TdApi.DisableProxy(), null) }
            return
        }
        val type = when (settings.kind) {
            ProxyKind.MTProto -> TdApi.ProxyTypeMtproto(settings.normalizedSecret())
            ProxyKind.Socks5 ->
                TdApi.ProxyTypeSocks5(settings.username, settings.password)
            ProxyKind.Http ->
                TdApi.ProxyTypeHttp(settings.username, settings.password, false)
        }
        runCatching {
            c.send(TdApi.AddProxy(settings.host, settings.port, true, type)) { result ->
                if (result is TdApi.Proxy) {
                    c.send(TdApi.EnableProxy(result.id)) { enabled ->
                        if (enabled is TdApi.Ok && resetNetwork) forceReconnect(settings)
                    }
                }
            }
        }
    }

    /**
     * Nudges TDLib to re-evaluate the network without dropping it.
     *
     * Changing the proxy does not, on its own, disturb a connection attempt
     * that is already backing off, and the device log shows the cost: with a
     * proxy whose round trip runs to 1.4 s the config fetch fails and
     * [ConfigManager] puts the next attempt 151 seconds away, so a corrected
     * proxy saved during that window did nothing for two and a half minutes.
     *
     * This used to answer that by setting the network type to none and back.
     * That was the wrong tool and it did damage: dropping the network kills
     * queries that are mid-flight, including the handshakes of every session
     * that was finally making progress, and they are not restarted where they
     * were. Announcing the type on its own is enough to wake the client — it
     * is the change TDLib listens for, not the gap — and it costs no
     * connection to say it.
     */
    private fun forceReconnect(settings: ProxySettings) {
        val c = client ?: return
        appScope.launch {
            runCatching {
                c.send(TdApi.SetNetworkType(TdApi.NetworkTypeOther()), null)
            }
            // Logged, because if this is the thing that stops working the app
            // looks exactly as it did before it was added: connected, silent.
            TrackLog.d(
                "TelegramScan",
                "proxy applied ${settings.host}:${settings.port} (${settings.kind}) — network announced",
            )
        }
    }

    /**
     * Asks TDLib to try this proxy and reports the round trip in seconds, or
     * -1.0 if it could not be reached.
     *
     * `testProxy` opens the proxy, walks it through to a Telegram datacenter
     * and answers `Ok` or an `Error` — it does not report how long that took,
     * so the time is measured here. The first version of this waited for a
     * `Seconds` result that never comes, and so reported every proxy as
     * unreachable: the device log shows four tests answering `Ok` in 2.4 to
     * 3.5 seconds each while the screen said "could not reach Telegram" for
     * all four.
     *
     * Fifteen seconds, not five. A working Iranian proxy measured 2.4-3.5s
     * here, and the cost of being impatient is calling a slow proxy dead.
     *
     * DC 4, because that is the datacenter the scan leans on, and a proxy that
     * reaches one datacenter and not another is a real state worth knowing.
     */
    suspend fun pingProxy(settings: ProxySettings, timeoutSeconds: Double = 15.0): Double {
        val c = client ?: return -1.0
        val type = when (settings.kind) {
            ProxyKind.MTProto -> TdApi.ProxyTypeMtproto(settings.normalizedSecret())
            ProxyKind.Socks5 -> TdApi.ProxyTypeSocks5(settings.username, settings.password)
            ProxyKind.Http -> TdApi.ProxyTypeHttp(settings.username, settings.password, false)
        }
        val startedAt = System.currentTimeMillis()
        return suspendCancellableCoroutine { cont ->
            runCatching {
                c.send(TdApi.TestProxy(settings.host, settings.port, type, 4, timeoutSeconds)) { result ->
                    cont.resume(
                        if (result is TdApi.Ok) {
                            (System.currentTimeMillis() - startedAt) / 1000.0
                        } else {
                            -1.0
                        },
                    )
                }
            }.onFailure { cont.resume(-1.0) }
        }
    }

    private var lastDirectPing: Pair<Long, Double>? = null

    /**
     * Measures proxy ping via TDLib TestProxy.
     * Returns [ProxyVerdict] with proxiedSeconds (-1.0 if unreachable).
     */
    suspend fun pingProxyVerdict(settings: ProxySettings, timeoutSeconds: Double = 15.0): ProxyVerdict {
        val proxiedSeconds = pingProxy(settings, timeoutSeconds)
        return ProxyVerdict(directSeconds = -1.0, proxiedSeconds = proxiedSeconds)
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
        _currentUser.value = null
        send(TdApi.LogOut())
        parametersSent = false
    }

    override suspend fun restart() {
        val creds = credentials ?: runCatching { TelegramConfig.credentials() }.getOrNull()
        try {
            client?.send(TdApi.Close(), null)
        } catch (_: Throwable) {}
        client = null
        parametersSent = false
        publishAuth(TelegramAuthState.Initializing)
        if (creds != null) {
            kotlinx.coroutines.delay(150)
            start(creds)
        }
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
        // No OpenChat here. SearchChatMessages does not need the chat to be
        // open, and the call was made but CloseChat never was: every chat this
        // scan touched stayed "open" for TDLib's lifetime, keeping its sync
        // handlers alive. It also fired in a burst of six at the start of
        // every scan, on top of discovery's own traffic — the blast that
        // Telegram answers with FLOOD_WAIT.
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
            nextFromMessageId = found.nextFromMessageId,
            totalCount = found.totalCount,
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
    private suspend fun forceFetchChat(chatId: Long): TdApi.Chat = when {
        // Private: chatId IS the user id.
        chatId > 0 -> send(TdApi.CreatePrivateChat(chatId, false))
        // Supergroup/channel: chatId = -1_000_000_000_000 - supergroupId, so
        // the id comes back by subtracting that marker. Driving it the other
        // way ("-marker - chatId") gives the same answer ONLY for ids at or
        // below the marker; a basic group's -123456789 lands in between and
        // produces -999876543211, which CreateSupergroupChat rejects with
        // SUPERGROUP_ID_INVALID — that is how 8 private groups went missing.
        chatId <= -1_000_000_000_000L ->
            send(TdApi.CreateSupergroupChat(-chatId - 1_000_000_000_000L, false))
        // Basic group: chatId = -basicGroupId, no marker.
        else -> send(TdApi.CreateBasicGroupChat(-chatId, false))
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

    /**
     * Session cache for the three list-free sources that actually cost a
     * network round trip.
     *
     * Audio discovery walks up to 20 SearchMessages pages, GetContacts pulls
     * every saved user, and each contact then costs a CreatePrivateChat (a
     * users.getFullUser for anyone not already cached). Chat lists and
     * GetChat are local reads and stay uncached — the whole point is to keep
     * the cheap path cheap and stop repeating the expensive one on every scan
     * of an unchanged account.
     *
     * A short TTL, not "once per process": a newly posted track or a newly
     * saved contact should show up within minutes, and none of these answers
     * is large enough to be worth invalidating early.
     */
    @Volatile private var slowSourcesAt = 0L
    @Volatile private var cachedAudioChatIds: LongArray = longArrayOf()
    @Volatile private var cachedContactIds: LongArray = longArrayOf()

    private fun slowSourcesFresh(now: Long): Boolean = now - slowSourcesAt < TelegramConfig.SLOW_SOURCES_TTL_MS


    /**
     * Account-wide audio discovery, one round trip per page.
     *
     * Runs only when [slowSourcesFresh] says the session cache is stale — the
     * caller decides, because this is the single most expensive thing the
     * candidate pass does (up to 20 SearchMessages pages against the server).
     */
    private suspend fun fetchAudioChatIds(): Pair<List<Long>, Boolean> {
        val ids = mutableListOf<Long>()
        var offset = ""
        var pages = 0
        while (pages < TelegramConfig.AUDIO_DISCOVERY_PAGES) {
            val found = runCatchingCancellable<TdApi.FoundMessages> {
                send(
                    TdApi.SearchMessages(
                        null,
                        "",
                        offset,
                        TelegramConfig.AUDIO_DISCOVERY_PAGE_SIZE,
                        TdApi.SearchMessagesFilterAudio(),
                        null,
                        0,
                        0,
                    ),
                )
            }.getOrNull() ?: break
            pages++
            found.messages.forEach { ids += it.chatId }
            if (found.nextOffset.isEmpty()) break
            offset = found.nextOffset
        }
        TrackLog.d(
            TAG,
            "audio discovery: pages=$pages messages=" +
                "${pages * TelegramConfig.AUDIO_DISCOVERY_PAGE_SIZE} " +
                "chats=${ids.toHashSet().size} complete=${pages > 0}",
        )
        // false when not even one page answered: that is a connectivity
        // failure, not an account with no audio, and the caller must not
        // stamp the TTL over it.
        return ids to (pages > 0)
    }

    /**
     * Saved contacts as chat ids, one round trip for the list plus one
     * CreatePrivateChat per contact (users.getFullUser for anyone TDLib has
     * not cached). The second-most expensive thing the candidate pass does,
     * and the reason it sits behind the caller's TTL.
     */
    private suspend fun fetchContactIds(knownChatIds: Set<Long>): List<Long> {
        // GetContacts answers with USER ids, not chat ids — CreatePrivateChat
        // is the bridge, and with forceImport=false it returns the existing
        // dialog rather than minting one. This is where the missing personal
        // chats come from: the dialog list only carries chats with a top
        // message, GetContacts carries everyone saved.
        val userIds = runCatchingCancellable { send(TdApi.GetContacts()) }
            .getOrNull()?.userIds
            ?: return emptyList()

        // For a private chat the user id IS the chat id, so anyone already in
        // a dialog list is already resolved — asking again is a wasted round
        // trip. On the real account this clears most of the pass before a
        // single request leaves the device.
        val resolved = ArrayList<Long>(userIds.size)
        val unknown = ArrayList<Long>(userIds.size)
        for (userId in userIds) {
            if (userId in knownChatIds) resolved += userId else unknown += userId
        }

        // Bounded: contact resolution is flood-controlled on the server side,
        // so 8 — matching the scan's own parallelism — is the ceiling, not a
        // number to push.
        val semaphore = Semaphore(CONTACT_PARALLEL)
        val fetched = coroutineScope {
            unknown.map { userId ->
                async {
                    semaphore.withPermit {
                        val chat = runCatchingCancellable {
                            send(TdApi.CreatePrivateChat(userId, false))
                        }.getOrNull()
                        if (chat == null) {
                            // A flood error must never be hammered: Telegram
                            // escalates the penalty on retry. Whatever resolved
                            // stands; the TTL re-runs the rest later.
                            TrackLog.d(TAG, "contact $userId unresolved — keeping the resolved set")
                        }
                        chat?.id
                    }
                }
            }.awaitAll().filterNotNull()
        }
        resolved += fetched
        TrackLog.d(TAG, "contacts: ${userIds.size} saved, ${unknown.size} needed a lookup, ${fetched.size} resolved")
        return resolved
    }

    /**
     * Everything the audio walk and the contact pass can already recognise
     * without a request: the dialog lists plus whatever Room has accumulated.
     * Used as the pre-filter that turns a contact into "already known".
     */
    private suspend fun candidateKnownIds(): Set<Long> {
        val known = LinkedHashSet<Long>()
        runCatchingCancellable { send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), TelegramConfig.MAX_TOP_CHATS)) }
            .getOrNull()
            ?.chatIds
            ?.forEach { known += it }
        return known
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
        // Two sources that never depend on a dialog list, because on this
        // account the lists are simply short: LoadChats answers NOT_FOUND
        // after ~144 main dialogs while the account really holds ~291 chats
        // that contain messages (the rest are empty history Telegram keeps
        // only in the export), and no limit or extra round changes that.
        //
        // GetChatFolder reads the folder DEFINITION rather than its list:
        // includedChatIds + pinnedChatIds name chats explicitly, even when
        // the chat has no active position and GetChats(ChatListFolder)
        // therefore never returns it.
        val explicitFolderIds = folderListIds.flatMap { folderId ->
            runCatchingCancellable<TdApi.ChatFolder> { send(TdApi.GetChatFolder(folderId)) }
                .getOrNull()
                ?.let { it.includedChatIds.toList() + it.pinnedChatIds.toList() }
                ?: emptyList()
        }.toLongArray()
        // The one source that reaches bot chats. GetContacts carries saved
        // people, folder definitions carry lists the user built, and both
        // came back empty for the 99 bot dialogs this account is missing —
        // a bot is in neither unless the user pinned it. Music Downloader
        // (1705 messages) was still absent after contacts+folders raised the
        // scan from 175 to 261 chats; this is the only path left that does
        // not consult a dialog list at all.
        //
        // SearchMessages with chatList=null searches the WHOLE account rather
        // than ChatListMain (what searchAllAudio hardcodes), and an empty
        // query is legal exactly BECAUSE a media filter is set — so every
        // answer is an audio message, and every audio message carries the
        // chatId it lives in. Bounded pages: enough to cover the recent
        // audio corpus without turning a list fetch into a full history walk.
        val now = System.currentTimeMillis()
        val audioChatIds: List<Long>
        val contactIds: List<Long>
        if (slowSourcesFresh(now)) {
            // Same answers, zero round trips: this is the path every repeat
            // scan of an unchanged account takes.
            audioChatIds = cachedAudioChatIds.toList()
            contactIds = cachedContactIds.toList()
        } else {
            // The two expensive walks are independent, so they overlap: the
            // 20-page audio search no longer waits behind contact resolution.
            val knownIds = candidateKnownIds()
            val (audioResult, contactsResult) = coroutineScope {
                val audio = async { fetchAudioChatIds() }
                val contacts = async { fetchContactIds(knownIds) }
                audio.await() to contacts.await()
            }
            val (fetchedAudio, audioComplete) = audioResult
            val fetchedContacts = contactsResult
            // A transient timeout on page 1 answers with an empty list; caching
            // THAT would blind the app for a full TTL and hide chats the
            // previous fetch had already found. Only an empty result over an
            // empty cache is allowed to stick — a real zero on first run.
            if (fetchedAudio.isNotEmpty() || (audioComplete && cachedAudioChatIds.isEmpty())) {
                cachedAudioChatIds = fetchedAudio.toLongArray()
            } else if (!audioComplete && cachedAudioChatIds.isNotEmpty()) {
                TrackLog.w(TAG, "audio discovery came back empty; keeping the previous answer")
            }
            // Union, never replace. A pass that lost ten contacts to a
            // transient error would otherwise erase them for a whole TTL —
            // and monotonicity says a chat we have already found is never
            // given back. This is what makes the parallel pass safe.
            if (fetchedContacts.isNotEmpty() || cachedContactIds.isEmpty()) {
                val merged = LinkedHashSet<Long>(cachedContactIds.size + fetchedContacts.size)
                cachedContactIds.forEach { merged += it }
                fetchedContacts.forEach { merged += it }
                cachedContactIds = merged.toLongArray()
            }
            audioChatIds = cachedAudioChatIds.toList()
            contactIds = cachedContactIds.toList()
            // Opening the app while offline must not lock audio discovery out
            // for a whole TTL: only stamp when at least one page really
            // answered, so the next scan simply retries.
            if (audioComplete) slowSourcesAt = now
        }
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
            folderIds.filter { seen.add(it) } +
            explicitFolderIds.filter { seen.add(it) } +
            contactIds.filter { seen.add(it) } +
            audioChatIds.filter { seen.add(it) }
        // The counts ARE the diagnosis: main 0 means the dialog sync hasn't
        // finished (LibraryScanner awaits and retries); archive > 0 is the
        // only way to see the fix above actually firing on a real account.
        // explicit/contacts are reported SEPARATELY because "merged went up"
        // alone cannot say which list-free source paid off — and if both add
        // zero, the next question is whether the lists or the sources are
        // what to distrust.
        TrackLog.d(
            TAG,
            "candidates: main=${mainIds.size} archive=${archiveIds.size} " +
                "folders=${folderIds.size} explicit=${explicitFolderIds.size} " +
                "contacts=${contactIds.size} audio=${audioChatIds.toHashSet().size} " +
                "merged=${chatIds.size}",
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
        // The counts alone cannot answer "is chat X here" — only the titles
        // can, and without them the question ends in guesswork every time.
        // One line per pass, sorted, so a missing chat is a name to look for
        // rather than a number to argue about.
        val found = infos.filterNotNull()
        TrackLog.d(
            TAG,
            "candidate titles (${found.size}): " +
                found.map { it.title }.sorted().joinToString(" | "),
        )
        return found
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

    override suspend fun fetchCurrentUser(): TelegramUser? = runCatchingCancellable {
        awaitReady()
        val me = send(TdApi.GetMe())
        val fullName = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ")
        val username = me.usernames?.activeUsernames?.firstOrNull() ?: me.usernames?.editableUsername
        val photoUrl = me.profilePhoto?.let {
            persistCoverFile(it.big) ?: persistCoverFile(it.small)
        }
        val user = TelegramUser(
            id = me.id,
            firstName = me.firstName,
            lastName = me.lastName,
            fullName = if (fullName.isNotBlank()) fullName else (username ?: "User"),
            username = username?.takeIf { it.isNotBlank() },
            phoneNumber = me.phoneNumber,
            photoUrl = photoUrl,
            isPremium = me.isPremium,
        )
        _currentUser.value = user
        user
    }.onFailure { e ->
        TrackLog.w(TAG, "fetchCurrentUser failed: ${e.message}")
    }.getOrNull()

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

    override suspend fun embeddedTrackCoverUrl(
        fileId: String?,
        expectedUniqueId: String?,
        chatId: Long,
        messageId: Long,
    ): String? {
        awaitReady()
        var currentFileId = fileId
        var currentUnique = expectedUniqueId

        // 1. If uniqueId is known, check if we ALREADY extracted this embedded cover to disk
        val coversDir = File(appContext.filesDir, "covers").apply { mkdirs() }
        val lookupKey = currentUnique?.takeIf { it.isNotBlank() } ?: currentFileId
        if (!lookupKey.isNullOrBlank()) {
            for (ext in listOf("jpg", "png", "webp")) {
                val cached = File(coversDir, "emb_$lookupKey.$ext")
                if (cached.exists() && cached.length() > 0) {
                    return Uri.fromFile(cached).toString()
                }
            }
        }

        // 2. Resolve file from TDLib
        var id = currentFileId?.toIntOrNull()
        var file: TdApi.File? = id?.let {
            runCatchingCancellable { send(TdApi.GetFile(it)) }.getOrNull()
        }

        val isStale = file == null ||
            (!currentUnique.isNullOrBlank() && !file.remote?.uniqueId.isNullOrBlank() && file.remote?.uniqueId != currentUnique)

        if ((isStale || file?.local?.path.isNullOrBlank()) && chatId != 0L && messageId > 0L) {
            val fresh = runCatchingCancellable { getAudioByMessage(chatId, messageId) }.getOrNull()
            val freshId = fresh?.remoteFileId
            val freshUnique = fresh?.uniqueFileId?.ifBlank { null } ?: currentUnique
            if (freshId != null && freshId != currentFileId) {
                currentFileId = freshId
                currentUnique = freshUnique
                id = freshId.toIntOrNull()
                if (id != null) {
                    file = runCatchingCancellable { send(TdApi.GetFile(id)) }.getOrNull()
                }
            }
        }

        val targetFile = file ?: return null
        val unique = targetFile.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: currentUnique ?: currentFileId ?: return null

        val path = targetFile.local?.path
        if (path.isNullOrBlank()) return null

        val art = runCatchingCancellable { EmbeddedArt.largest(File(path)) }
            .getOrElse {
                TrackLog.w(TAG, "Embedded art read failed for fileId $currentFileId: ${it.message}")
                return null
            } ?: return null

        val ext = if (art.mimeType == "image/png") "png" else if (art.mimeType == "image/webp") "webp" else "jpg"
        val target = File(coversDir, "emb_$unique.$ext")
        if (!target.exists()) {
            runCatchingCancellable { target.writeBytes(art.bytes) }
                .getOrElse {
                    TrackLog.w(TAG, "Embedded art write failed for $currentFileId: ${it.message}")
                    return null
                }
        }
        TrackLog.d(TAG, "Embedded art for fileId $currentFileId -> ${target.name} (${art.bytes.size} bytes, ${art.mimeType})")
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
