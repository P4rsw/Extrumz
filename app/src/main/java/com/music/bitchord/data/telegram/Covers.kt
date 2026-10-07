package com.music.bitchord.data.telegram

import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.sources.SourceRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Artwork, fetched the way a channel scan is run: only for whatever is actually
 * on screen, deduplicated so the same cover is never in flight twice, throttled
 * to two requests at once, and silent while a scan is indexing — a fast scroll
 * costs cancelled requests, not a queue and not a data bill.
 *
 * Two tiers:
 *
 *  - Tier 1 (`ensure*`) fills an EMPTY row avatar from Telegram's inline
 *    minithumbnail — one small local RPC, no file download. Each row pays at
 *    most one of these ever; a URL already on the row short-circuits before
 *    any work.
 *  - Tier 2 (`*Hq`) downloads the full-size photo/cover into `covers/` — once
 *    per unique file across sessions, cached on disk — and rewrites that row's
 *    cover/photo column, so one write reaches every surface reading the Flow
 *    (rows, shelf cards, player) at once. Callers pass the URL they already
 *    hold, so an already-upgraded row never issues an RPC.
 */
object Covers {

    /** A covers/hq_* URL means this row already carries full-size artwork. */
    const val HQ_PREFIX = "covers/hq_"

    private val active = ConcurrentHashMap<String, Boolean>()
    private val gate = Semaphore(2)

    /** (channelId, messageId) from a track id ("tg_-100…_12345") or a videoId. */
    fun tgIds(videoId: String?): Pair<Long, Long>? {
        if (videoId.isNullOrBlank()) return null
        val trackId = SourceRegistry.parseTrackKey(videoId)?.second ?: videoId
        if (!trackId.startsWith("tg_")) return null
        val body = trackId.removePrefix("tg_")
        val cut = body.lastIndexOf('_')
        if (cut <= 0) return null
        val chatId = body.substring(0, cut).toLongOrNull() ?: return null
        val messageId = body.substring(cut + 1).toLongOrNull() ?: return null
        if (chatId == 0L || messageId <= 0L) return null
        return chatId to messageId
    }

    /** Tier 1 for one track; [current] already on the row short-circuits. */
    suspend fun ensureTrackCover(chatId: Long, messageId: Long, current: String?): String? {
        if (!current.isNullOrBlank()) return current
        return fetchOnce("t:$chatId:$messageId") {
            val url = Telegram.client.getAudioByMessage(chatId, messageId)?.coverUrl
            if (url.isNullOrBlank()) return@fetchOnce null
            TelegramDb.trackDao.setCoverIfEmpty(chatId, messageId, url)
            url
        }
    }

    /** Tier 1 driven by whatever videoId/Song a row was handed. */
    suspend fun ensureTrackCover(videoId: String?, current: String?): String? {
        val ids = tgIds(videoId) ?: return current
        return ensureTrackCover(ids.first, ids.second, current)
    }

    /** Tier 1 for one chat avatar; GetChat itself is served locally by TDLib. */
    suspend fun ensureChatPhoto(chatId: Long, current: String?): String? {
        if (!current.isNullOrBlank()) return current
        return fetchOnce("p:$chatId") {
            val url = Telegram.client.getChatInfo(chatId)?.photoUrl
            if (url.isNullOrBlank()) return@fetchOnce null
            TelegramDb.chatDao.setPhotoUrlIfEmpty(chatId, url)
            url
        }
    }

    /** Tier 2: the full-size cover behind a track id; falls back to [current]. */
    suspend fun trackCoverHq(videoId: String?, current: String?): String? {
        val ids = tgIds(videoId) ?: return current
        return trackCoverHq(ids.first, ids.second, current)
    }

    /** Tier 2: the full-size cover for one track; falls back to [current]. */
    suspend fun trackCoverHq(chatId: Long, messageId: Long, current: String?): String? {
        if (!current.isNullOrBlank() && current.contains(HQ_PREFIX)) return current
        return fetchOnce("ht:$chatId:$messageId") {
            val url = Telegram.client.downloadTrackCover(chatId, messageId) ?: return@fetchOnce current
            TelegramDb.trackDao.setCover(chatId, messageId, url)
            url
        } ?: current
    }

    /** Tier 2: the full-size photo for one chat; falls back to [current]. */
    suspend fun chatPhotoHq(chatId: Long, current: String?): String? {
        if (!current.isNullOrBlank() && current.contains(HQ_PREFIX)) return current
        return fetchOnce("hp:$chatId") {
            val url = Telegram.client.downloadChatPhoto(chatId) ?: return@fetchOnce current
            TelegramDb.chatDao.setPhotoUrl(chatId, url)
            url
        } ?: current
    }

    /**
     * Runs [block] once per key: a second caller for the same cover finds the
     * key taken and returns null instead of racing the first — the winner's DB
     * write reaches the loser's row through its Flow anyway. Null also means
     * "keep what you have": unauthorized, a scan is indexing, or a request is
     * already in flight. Disk work happens off the main thread; everything the
     * caller wanted it for (a row's avatar) has already failed over to the
     * placeholder by then.
     */
    private suspend fun fetchOnce(key: String, block: suspend () -> String?): String? {
        if (Telegram.client.authState.value !is TelegramAuthState.Ready) return null
        if (Telegram.scanner.progress.value.isRunning) return null
        if (active.putIfAbsent(key, true) != null) return null
        try {
            return withContext(Dispatchers.IO) { gate.withPermit { block() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        } finally {
            active.remove(key)
        }
    }
}
