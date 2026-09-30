package com.music.bitchord.data.telegram

import com.music.bitchord.data.db.entity.Track

/**
 * Merge priority: ID3 tags (when available) > Telegram audio fields >
 * caption parsing > filename.
 */
object MetadataMerger {

    data class Id3Tags(
        val title: String? = null,
        val performer: String? = null,
        val album: String? = null,
        val trackNumber: Int? = null,
        val genre: String? = null,
        val year: Int? = null,
        val lyrics: String? = null,
        val coverPath: String? = null
    )

    fun merge(
        remote: RemoteAudio,
        nowMillis: Long,
        id3: Id3Tags? = null,
        existing: Track? = null
    ): Track {
        val captionTags = parseCaption(remote.caption)
        val filenameTitle = remote.fileName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }

        val title = firstNonBlank(
            id3?.title,
            remote.title,
            captionTags.title,
            filenameTitle,
            existing?.title
        ) ?: ""
        val performer = firstNonBlank(
            id3?.performer,
            remote.performer,
            captionTags.performer,
            existing?.performer
        ) ?: ""
        val album = firstNonBlank(id3?.album, captionTags.album, existing?.album) ?: ""
        val genre = firstNonBlank(id3?.genre, captionTags.genre, existing?.genre) ?: ""
        val year = id3?.year ?: captionTags.year ?: existing?.year ?: 0
        val trackNumber = id3?.trackNumber ?: existing?.trackNumber ?: 0
        val lyrics = firstNonBlank(id3?.lyrics, existing?.lyrics) ?: ""
        val coverUrl = firstNonBlank(id3?.coverPath, existing?.coverUrl) ?: ""

        val hashtags = extractHashtags(remote.caption)

        return Track(
            id = existing?.id ?: 0L,
            fileUniqueId = remote.uniqueFileId,
            fileId = remote.remoteFileId,
            performer = performer,
            title = title,
            duration = remote.durationSeconds,
            album = album,
            trackNumber = trackNumber,
            genre = genre,
            year = year,
            coverUrl = coverUrl,
            lyrics = lyrics,
            dateAdded = existing?.dateAdded ?: (remote.dateSeconds * 1000L).takeIf {
                it > 0
            } ?: nowMillis,
            sourceChatId = remote.chatId,
            sourceMessageId = remote.messageId,
            mediaAlbumId = remote.mediaAlbumId,
            isFavorite = existing?.isFavorite ?: false,
            playCount = existing?.playCount ?: 0,
            lastPlayed = existing?.lastPlayed,
            tags = (existing?.tags?.split(',')?.filter { it.isNotBlank() } ?: emptyList())
                .plus(hashtags)
                .distinct()
                .joinToString(","),
            fileSize = remote.sizeBytes,
            mimeType = remote.mimeType,
            caption = remote.caption
        )
    }

    private data class CaptionMeta(
        val title: String? = null,
        val performer: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val year: Int? = null
    )

    private fun parseCaption(caption: String): CaptionMeta {
        if (caption.isBlank()) return CaptionMeta()
        val lines = caption.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = lines.firstOrNull { !it.startsWith("#") && !it.startsWith("@") }
        val performer = lines.getOrNull(1)?.takeIf {
            !it.startsWith("#") && !it.startsWith("@")
        }
        return CaptionMeta(title = title, performer = performer)
    }

    private fun extractHashtags(caption: String): List<String> =
        Regex("#(\\p{L}[\\p{L}\\p{N}_]*)")
            .findAll(caption)
            .map { it.groupValues[1] }
            .toList()

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
