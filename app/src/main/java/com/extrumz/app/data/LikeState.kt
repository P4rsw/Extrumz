package com.extrumz.app.data

import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.db.entity.LikedVideo
import com.extrumz.app.data.model.LikeStatus
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.telegramCoordsOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ratings, and the only place one is ever changed.
 *
 * This used to expose `set(videoId, status)` and leave persistence to its
 * callers. Five surfaces call it — the song menu, the in-app player, Now
 * Playing, the landscape player and the playback notification — and exactly
 * one of them remembered to write the like down. A song liked from the player
 * was gone by the next launch, and it was not the caller's mistake to make:
 * a write that has to be remembered in five places will be forgotten in four.
 *
 * So [set] takes a `Song` and does both halves itself. A bare video id is not
 * enough to write down — a like has to carry enough to be drawn later — which
 * is the reason the type changed rather than the discipline.
 *
 * The map is a cache of what is in `liked_videos`, kept because playback runs
 * in the same process and needs a rating without a database read.
 */
object LikeState {
    private val _overrides = MutableStateFlow<Map<String, LikeStatus>>(emptyMap())
    val overrides: StateFlow<Map<String, LikeStatus>> = _overrides.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var seeded = false

    /** The rating as this process knows it. Absent means never rated here. */
    fun statusOf(videoId: String): LikeStatus = _overrides.value[videoId] ?: LikeStatus.INDIFFERENT

    /**
     * THE write. Memory and disk, always together, so no caller can do half.
     *
     * The disk half is fire-and-forget on purpose: a heart has to answer the
     * tap, and a rating is a low-stakes one-tap action with nothing to roll
     * back to. It is wrapped rather than left bare because a failure there
     * used to leave memory and disk disagreeing with nothing to say so.
     */
    fun set(song: Song, status: LikeStatus) {
        if (statusOf(song.videoId) == status) return
        _overrides.value += (song.videoId to status)
        persist(song, status)
    }

    /**
     * Memory only, for a rating that has no song behind it — the thumbs-down,
     * which was never stored, and any caller holding just an id. Nothing is
     * written, because there is nothing to write.
     */
    fun remember(videoId: String, status: LikeStatus) {
        _overrides.value += (videoId to status)
    }

    /** For a caller with only an id. Records the rating; nothing is stored. */
    fun rememberStated(videoId: String, stated: LikeStatus?) {
        if (stated != null && stated != LikeStatus.INDIFFERENT && videoId !in _overrides.value) {
            _overrides.value += (videoId to stated)
        }
    }

    /**
     * Loads what was written down. Called from the application's own startup
     * and not from a ViewModel: a process started only for the playback
     * service never builds one, and the notification would then show a liked
     * song as unrated.
     */
    suspend fun seed() {
        if (seeded) return
        runCatching { TelegramDb.likedDao.ids() }
            .onSuccess { ids ->
                if (ids.isNotEmpty()) {
                    val next = _overrides.value.toMutableMap()
                    ids.forEach { next.putIfAbsent(it, LikeStatus.LIKE) }
                    _overrides.value = next
                }
                seeded = true
            }
    }

    /**
     * Forgets the session's ratings. Deliberately does NOT touch the table:
     * a rating here is device-local — nothing is sent anywhere — so clearing
     * them on a sign-out would destroy the user's own list with no copy
     * anywhere to restore from. Only removing the database itself should.
     */
    fun clear() {
        _overrides.value = emptyMap()
        seeded = false
    }

    private fun persist(song: Song, status: LikeStatus) {
        val dao = runCatching { TelegramDb.likedDao }.getOrNull() ?: return
        scope.launch {
            runCatching {
                if (status == LikeStatus.LIKE) {
                    dao.upsert(song.toLikedRow(System.currentTimeMillis()))
                } else {
                    dao.delete(song.videoId)
                }
            }
        }
    }
}

/**
 * The stored form of a like.
 *
 * The display fields are copied rather than joined back to `tracks` later,
 * because most liked songs have no row there: a search result or a video has
 * never been scanned into this device. The Telegram coordinates are read out
 * of the id rather than off the `Song`, because `Song.sourceChatId` is only
 * filled by the mapper that builds rows from the table — a song rebuilt from
 * a `MediaItem`, which is what the notification has, carries none.
 */
internal fun Song.toLikedRow(now: Long): LikedVideo {
    val coords = telegramCoordsOf(videoId)
    return LikedVideo(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        sourceChatId = coords?.first ?: 0L,
        sourceMessageId = coords?.second ?: 0L,
        likedAt = now,
    )
}
