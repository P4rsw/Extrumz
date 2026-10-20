package com.extrumz.app.data.sources

import android.net.Uri
import android.provider.MediaStore
import com.extrumz.app.data.TrackLog
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.data.telegram.TelegramConfig
import kotlinx.coroutines.flow.first
import androidx.room.RoomRawQuery
import java.io.File
import kotlin.math.abs
import com.extrumz.app.data.model.toSong

private const val TAG = "Extrumz"

/**
 * The listener's own Telegram library as a [MusicSource].
 *
 * Search answers from Room's FTS5 index first (offline, instant) and falls
 * back to a TDLib network search that indexes what it finds, so the next
 * offline search has it. Streams resolve in two tiers: an already-readable
 * MediaStore copy under `/Telegram/` when one matches, otherwise the file is
 * pulled through TDLib once and served from app storage as `file://` —
 * Extrumz playback accepts that scheme directly.
 *
 * Track ids are `tg_<chatId>_<messageId>` (inside [SourceRegistry.trackKey]),
 * which is stable across re-scans and does not depend on TDLib's rotating
 * numeric file ids.
 */
class TelegramSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.TELEGRAM
    override val displayName: String get() = config.label.ifBlank { SourceKind.TELEGRAM.label }

    override suspend fun health(): SourceHealth =
        when (val state = Telegram.client.authState.value) {
            is TelegramAuthState.Ready -> SourceHealth.Ok("متصل به تلگرام")
            is TelegramAuthState.Failed ->
                SourceHealth.Rejected(state.message.ifBlank { "ورود به تلگرام ناموفق بود" })
            is TelegramAuthState.Initializing ->
                SourceHealth.Rejected("در حال راه‌اندازی تلگرام…")
            is TelegramAuthState.WaitingForCode ->
                SourceHealth.Rejected("در انتظار کد تأیید")
            is TelegramAuthState.WaitingForPassword ->
                SourceHealth.Rejected("در انتظار رمز دو مرحله‌ای")
            is TelegramAuthState.WaitingForQrScan ->
                SourceHealth.Rejected("در انتظار اسکن کد QR")
            is TelegramAuthState.WaitingForPhoneNumber ->
                SourceHealth.Rejected("در انتظار شماره تلفن")
            is TelegramAuthState.Closed ->
                SourceHealth.Rejected("نشست تلگرام بسته شده است")
            is TelegramAuthState.Uninitialized ->
                SourceHealth.Rejected("وارد تلگرام نشده‌اید")
        }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val local = localResults(trimmed, limit)
        val authorized = Telegram.client.authState.value is TelegramAuthState.Ready
        if (!authorized) return local
        if (local.isNotEmpty() && !waitForAll) return local

        // Network fallback: index anything new so the offline index grows.
        val remote = runCatching {
            Telegram.client.searchAllAudio(trimmed, TelegramConfig.DEFAULT_PAGE_LIMIT)
        }.getOrElse {
            TrackLog.w(TAG, "Telegram search failed: ${it.message}")
            emptyList()
        }
        if (remote.isEmpty()) return local
        remote.forEach { audio ->
            runCatching { Telegram.scanner.indexAudio(audio) }
        }
        return localResults(trimmed, limit)
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val (chatId, messageId) = parseTrackId(trackId) ?: run {
            TrackLog.w(TAG, "Telegram stream: bad track id $trackId")
            return null
        }
        val track = TelegramDb.trackDao.getByChatAndMessage(chatId, messageId)
            ?: run {
                TrackLog.w(TAG, "Telegram stream: no row for $trackId")
                return null
            }

        // Tier 1: a readable local copy in the device library (/Telegram/).
        mediaStoreTierOne(track)?.let { uri ->
            TrackLog.d(TAG, "Telegram tier-1 MediaStore hit for $trackId")
            return SourceStream(
                url = uri,
                format = StreamFormat(codec = codecOf(track.mimeType)),
                durationSec = track.duration,
            )
        }

        // Tier 3: pull through TDLib (once) and serve the local file. Two
        // failures land here: a hard "Not Found" on GetFile/DownloadFile, and
        // a STALE identity — the stored fileId resolving to a different file
        // (StaleFileIdException), which would otherwise play the wrong track
        // while the UI shows this one's title. Both get one refresh from the
        // message itself (fresh audio.id AND the durable unique id) and one
        // retry, with all ids logged to make the next occurrence diagnosable.
        val path = runCatching {
            Telegram.client.getStreamPath(track.fileId, expectedUniqueId = track.fileUniqueId)
        }.getOrElse { firstFailure ->
            val fresh = runCatching {
                Telegram.client.getAudioByMessage(chatId, messageId)
            }.getOrNull()
            val freshId = fresh?.remoteFileId
            val freshUnique = fresh?.uniqueFileId?.ifBlank { null }
            val uniqueChanged = freshUnique != null && freshUnique != track.fileUniqueId
            if (freshId == null || (freshId == track.fileId && !uniqueChanged)) {
                TrackLog.w(
                    TAG,
                    "Telegram tier-3 download failed: ${firstFailure.message} " +
                        "(fileId ${track.fileId}, no fresher id)",
                )
                return null
            }
            TrackLog.w(
                TAG,
                "Telegram tier-3 download failed: ${firstFailure.message}; " +
                    "refreshing fileId ${track.fileId} -> $freshId " +
                    "unique ${track.fileUniqueId} -> $freshUnique and retrying",
            )
            // Keep the fresh ids so the next play doesn't repeat the dance.
            val refreshed = track.copy(
                fileId = freshId,
                fileUniqueId = freshUnique ?: track.fileUniqueId,
            )
            runCatching { TelegramDb.trackDao.update(refreshed) }
            runCatching {
                Telegram.client.getStreamPath(
                    refreshed.fileId,
                    expectedUniqueId = refreshed.fileUniqueId,
                )
            }.getOrElse { retryFailure ->
                TrackLog.w(
                    TAG,
                    "Telegram tier-3 retry failed: ${retryFailure.message} " +
                        "(fileId ${refreshed.fileId})",
                )
                return null
            }
        }
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        return SourceStream(
            url = Uri.fromFile(file).toString(),
            format = StreamFormat(codec = codecOf(track.mimeType)),
            durationSec = track.duration,
        )
    }

    // ── Local index ─────────────────────────────────────────────────────

    /**
     * FTS5 first (quoted tokens, phrase-safe), LIKE as the fallback for
     * databases whose FTS index is missing or mid-repair — see
     * [Fts5Migrations][com.extrumz.app.data.db.Fts5Migrations].
     */
    private suspend fun localResults(query: String, limit: Int): List<Song> {
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" ") { token ->
            val cleaned = token.replace("\"", "").trim()
            if (cleaned.isEmpty()) return@joinToString ""
            "\"$cleaned\""
        }.trim()
        val dao = TelegramDb.trackDao
        val rows = if (match.isNotBlank()) {
            val fts = runCatching {
                dao.searchFtsRaw(
                    RoomRawQuery(FTS_SEARCH_SQL) { stmt -> stmt.bindText(1, match) }
                ).first()
            }.getOrDefault(emptyList())
            if (fts.isNotEmpty()) fts else dao.search(query).first()
        } else {
            dao.search(query).first()
        }
        return rows.take(limit).map { it.toSong(config.id) }
    }

    // ── Tier 1: MediaStore /Telegram/ ───────────────────────────────────

    /**
     * Matches this track against readable MediaStore audio whose path lives
     * under a `/Telegram/` directory (files other Telegram clients saved).
     * Title must match exactly (case-insensitive, or `artist - title` as the
     * file name) and duration within 3 seconds — a title-only match would
     * happily hand over a different recording of the same song. Any query
     * failure (notably missing media permission) is a miss: the caller has a
     * Tier 3 that always works.
     */
    private fun mediaStoreTierOne(track: Track): String? = runCatching {
        val resolver = Telegram.context.contentResolver ?: return null
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
        )
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIdx) ?: continue
                val title = cursor.getString(titleIdx).orEmpty()
                val durationMs = cursor.getLong(durationIdx)
                val durationOk = track.duration == 0 ||
                    abs(durationMs / 1000 - track.duration) <= 3
                if (!durationOk) continue
                val name = File(path).nameWithoutExtension
                val titleOk = title.equals(track.title, ignoreCase = true) ||
                    name.equals(track.title, ignoreCase = true) ||
                    name.equals("${track.performer} - ${track.title}", ignoreCase = true)
                if (titleOk) return Uri.fromFile(File(path)).toString()
            }
            null
        }
    }.getOrNull()

    private fun trackIdFor(track: Track) = "tg_${track.sourceChatId}_${track.sourceMessageId}"

    private fun parseTrackId(trackId: String): Pair<Long, Long>? {
        if (!trackId.startsWith("tg_")) return null
        val parts = trackId.removePrefix("tg_").split('_')
        if (parts.size != 2) return null
        val chat = parts[0].toLongOrNull() ?: return null
        val message = parts[1].toLongOrNull() ?: return null
        return chat to message
    }

    private fun codecOf(mimeType: String): String? = when (mimeType.substringBefore('/')) {
        "audio" -> when (mimeType.substringAfter('/').substringBefore('.')) {
            "mpeg", "mp3" -> "mp3"
            "mp4", "aac" -> "mp4"
            "ogg", "opus" -> "opus"
            "flac" -> "flac"
            else -> mimeType.substringAfter('/')
        }
        else -> null
    }

    private fun durationText(seconds: Int): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    private companion object {
        val FTS_SEARCH_SQL = """
            SELECT t.* FROM tracks t
            INNER JOIN tracks_fts f ON t.id = f.rowid
            WHERE tracks_fts MATCH ?
            ORDER BY rank
            LIMIT 500
        """.trimIndent()
    }
}
