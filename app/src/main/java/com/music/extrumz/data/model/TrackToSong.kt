package com.extrumz.app.data.model

import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.sources.SourceKind
import com.extrumz.app.data.sources.SourceRegistry
import java.util.Locale

/**
 * The one `Track` -> `Song` mapping.
 *
 * There were two, and they disagreed. `TelegramSource` kept one for the
 * library and `SongsScreen` kept another for the songs list, and the second
 * had quietly lost the caption fallback, the `sourceQuality` marker and
 * `sourceChatId` — so the same row produced a different `Song` depending on
 * which screen built it. That is invisible until something downstream reads a
 * field only one of them filled in.
 *
 * Playlists need this too: their rows are `Track`s and playback wants `Song`s.
 * Rather than add a third copy, both callers now come here.
 */
internal fun Track.toSong(configId: String? = null): Song {
    // The caller that owns the config passes it; everything else looks up the
    // Telegram source, which is the only kind that produces rows of this type.
    val resolvedConfig = configId
        ?: SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.TELEGRAM }?.id
    return Song(
        videoId = resolvedConfig?.let { SourceRegistry.trackKey(it, telegramTrackId) }
            ?: telegramTrackId,
        title = title.ifBlank { caption.takeIf { it.isNotBlank() } ?: fileId },
        artist = performer.ifBlank { "نامشخص" },
        albumName = album.ifBlank { null },
        thumbnailUrl = coverUrl.ifBlank { null },
        durationText = formatTrackDuration(duration),
        sourceQuality = "HIGH",
        sourceChatId = sourceChatId,
        // The playlist-membership key, carried on the field the model already
        // reserves for exactly this: a row's identity within one playlist.
        // Removal is expressed in these terms, and `videoId` is not one — the
        // DELETE matches on `trackFileUniqueId` and would silently match
        // nothing if it were handed a `videoId`.
        setVideoId = fileUniqueId,
    )
}

/** `tg_<chatId>_<messageId>`, the form `TelegramSource` parses back apart. */
internal val Track.telegramTrackId: String
    get() = "tg_${sourceChatId}_${sourceMessageId}"

/** `m:ss`, or empty when the row carries no duration. */
internal fun formatTrackDuration(seconds: Int): String =
    if (seconds <= 0) "" else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)


/**
 * The Telegram coordinates inside a `videoId`, or null when it is not one.
 *
 * A Telegram row's id is `src:<configId>::tg_<chatId>_<messageId>`. Three
 * places had grown their own copy of this split — the liked list, the
 * add-to-playlist sheet and this file — and three copies of a format is three
 * places to be wrong about it.
 */
internal fun telegramCoordsOf(videoId: String): Pair<Long, Long>? {
    val tail = videoId.substringAfterLast("::", missingDelimiterValue = "")
        .ifEmpty { videoId }
    if (!tail.startsWith("tg_")) return null
    val parts = tail.removePrefix("tg_").split("_")
    val chatId = parts.getOrNull(0)?.toLongOrNull() ?: return null
    val messageId = parts.getOrNull(1)?.toLongOrNull() ?: return null
    return chatId to messageId
}
