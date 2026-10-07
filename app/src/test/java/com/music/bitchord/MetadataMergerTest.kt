package com.music.bitchord

import com.music.bitchord.data.telegram.MetadataMerger
import com.music.bitchord.data.telegram.RemoteAudio
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MetadataMerger's two stage-3 inputs: Telegram's inline album cover (م۱/F8 —
 * the stream library had no cover source at all, so the app drew grey
 * squares while the system player showed art from the downloaded file) and
 * labelled caption lines (م۳).
 */
class MetadataMergerTest {

    private fun remote(
        caption: String = "",
        coverUrl: String? = null,
    ) = RemoteAudio(
        chatId = 1L,
        messageId = 2L,
        remoteFileId = "3",
        uniqueFileId = "uid-1",
        title = null,
        performer = null,
        fileName = "song.mp3",
        mimeType = "audio/mpeg",
        durationSeconds = 100,
        sizeBytes = 1_000L,
        caption = caption,
        dateSeconds = 1_700_000_000L,
        mediaAlbumId = null,
        coverUrl = coverUrl,
    )

    // --- covers -------------------------------------------------------------

    @Test
    fun `telegram minithumbnail cover lands in the track`() {
        val track = MetadataMerger.merge(remote(coverUrl = "file:///covers/1.jpg"), 0L)
        assertEquals("file:///covers/1.jpg", track.coverUrl)
    }

    @Test
    fun `local artwork outranks the telegram cover`() {
        val existing = MetadataMerger.merge(remote(coverUrl = "file:///id3.jpg"), 0L)
        val track = MetadataMerger.merge(
            remote(coverUrl = "file:///tg.jpg"),
            0L,
            existing = existing,
        )
        assertEquals("file:///id3.jpg", track.coverUrl)
    }

    @Test
    fun `a fresh telegram cover replaces a stale remote one`() {
        val existing = MetadataMerger.merge(remote(coverUrl = "https://cdn/old.jpg"), 0L)
        val track = MetadataMerger.merge(
            remote(coverUrl = "file:///new.jpg"),
            0L,
            existing = existing,
        )
        assertEquals("file:///new.jpg", track.coverUrl)
    }

    // --- labelled caption lines --------------------------------------------

    @Test
    fun `persian labelled lines fill album genre year without stealing the title`() {
        val track = MetadataMerger.merge(
            remote(
                caption = "خواننده اول\nخواننده دوم\nآلبوم: شب‌ها\nژانر: پاپ\nسال: 1397\n#تست",
            ),
            0L,
        )
        assertEquals("خواننده اول", track.title)
        assertEquals("خواننده دوم", track.performer)
        assertEquals("شب‌ها", track.album)
        assertEquals("پاپ", track.genre)
        assertEquals(1397, track.year)
        assertEquals("تست", track.tags)
    }

    @Test
    fun `english labels work and a leading label never becomes the title`() {
        val track = MetadataMerger.merge(
            remote(caption = "Album: Night\nTitle Line\nArtist Line"),
            0L,
        )
        assertEquals("Night", track.album)
        assertEquals("Title Line", track.title)
        assertEquals("Artist Line", track.performer)
    }

    @Test
    fun `unlabelled captions behave exactly as before`() {
        val track = MetadataMerger.merge(remote(caption = "Song Name\nArtist\n#rock"), 0L)
        assertEquals("Song Name", track.title)
        assertEquals("Artist", track.performer)
        assertEquals("", track.album)
        assertEquals(0, track.year)
        assertEquals("rock", track.tags)
    }
}
