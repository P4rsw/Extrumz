package com.extrumz.app.data.telegram

import com.extrumz.app.data.db.entity.Track

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
        // Local artwork (a downloaded file's own tags) outranks everything,
        // then ID3, then Telegram's inline minithumbnail — the streaming
        // library had NO cover source before this, which is why the app drew
        // grey squares while the system player (reading the downloaded file)
        // showed art.
        val coverUrl = firstNonBlank(
            existing?.coverUrl?.takeIf { isLocalCover(it) },
            id3?.coverPath,
            remote.coverUrl,
            existing?.coverUrl,
        ) ?: ""

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

    /**
     * `آلبوم: X` / `Album: X` metadata lines — the colon is required so a
     * song simply titled "سال نو" can never be mistaken for a year label.
     * Year values are kept verbatim (Persian channels usually write Jalali
     * years; storing 1397 beats storing 0).
     */
    private val LABELLED_LINE =
        Regex("""^(album|genre|year|آلبوم|ژانر|سال)\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)

    private val FOUR_DIGIT_YEAR = Regex("""\d{4}""")

    private fun parseCaption(caption: String): CaptionMeta {
        if (caption.isBlank()) return CaptionMeta()
        val lines = caption.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var album: String? = null
        var genre: String? = null
        var year: Int? = null
        for (line in lines) {
            val labelled = LABELLED_LINE.find(line) ?: continue
            val value = labelled.groupValues[2].trim()
            if (value.isEmpty()) continue
            when (labelled.groupValues[1].lowercase()) {
                "album", "آلبوم" -> album = value
                "genre", "ژانر" -> genre = value
                "year", "سال" -> year = FOUR_DIGIT_YEAR.find(value)?.value?.toIntOrNull()
            }
        }
        // Title/performer come from the UNLABELLED lines only: a metadata
        // line is metadata, never content.
        val plain = lines.filter { !LABELLED_LINE.matches(it) }
        val title = plain.firstOrNull { !it.startsWith("#") && !it.startsWith("@") }
        val performer = plain.getOrNull(1)?.takeIf {
            !it.startsWith("#") && !it.startsWith("@")
        }
        return CaptionMeta(title = title, performer = performer, album = album, genre = genre, year = year)
    }

    private fun extractHashtags(caption: String): List<String> =
        Regex("#(\\p{L}[\\p{L}\\p{N}_]*)")
            .findAll(caption)
            .map { it.groupValues[1] }
            .toList()

    private fun isLocalCover(url: String): Boolean =
        url.startsWith("file:") || url.startsWith("content:")

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
