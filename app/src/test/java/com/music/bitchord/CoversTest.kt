package com.music.bitchord

import com.music.bitchord.data.telegram.Covers
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F8 cover fill: every lazy fetch hangs off [Covers.tgIds], so a track id from
 * any surface (row, shelf card, player) must decode to exactly one channel and
 * one message — and anything that isn't a Telegram id must decode to nothing,
 * because a wrong id would fetch artwork for the wrong song.
 */
class CoversTest {

    @Test
    fun decodesBareTelegramTrackId() {
        assertEquals(-1001234567890L to 42L, Covers.tgIds("tg_-1001234567890_42"))
    }

    @Test
    fun decodesSourcePrefixedVideoId() {
        assertEquals(1258152843L to 7L, Covers.tgIds("src:cfg-1::tg_1258152843_7"))
    }

    @Test
    fun decodesPersonalChatId() {
        assertEquals(5478521520L to 108L, Covers.tgIds("tg_5478521520_108"))
    }

    @Test
    fun ignoresIdsFromOtherSources() {
        assertNull(Covers.tgIds(null))
        assertNull(Covers.tgIds(""))
        assertNull(Covers.tgIds("dQw4w9WgXcQ"))
        assertNull(Covers.tgIds("/storage/emulated/0/Music/a.mp3"))
        assertNull(Covers.tgIds("src:cfg-1::local-file-7"))
    }

    @Test
    fun localCoverPathAcceptsOnlyExistingLocalFiles() {
        val f = File.createTempFile("cover", ".jpg")
        try {
            f.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(f.path, Covers.localCoverPath("file://${f.path}"))
            assertNull(Covers.localCoverPath("file://${f.path}.missing"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun localCoverPathRejectsRemoteAndSchemless() {
        assertNull(Covers.localCoverPath(null))
        assertNull(Covers.localCoverPath(""))
        assertNull(Covers.localCoverPath("https://e-cdn.example/cover.jpg"))
        assertNull(Covers.localCoverPath("http://e-cdn.example/cover.jpg"))
        assertNull(Covers.localCoverPath("content://media/external/audio/media/9"))
        assertNull(Covers.localCoverPath("/plain/path/no-scheme.jpg"))
    }

    @Test
    fun ignoresMalformedTelegramIds() {
        assertNull(Covers.tgIds("tg_onlychannel")) // no message part
        assertNull(Covers.tgIds("tg_123_")) // empty message id
        assertNull(Covers.tgIds("tg_abc_12")) // non-numeric chat id
        assertNull(Covers.tgIds("tg_12_0")) // message ids start at 1
        assertNull(Covers.tgIds("tg_0_5")) // chat id 0 never exists
    }
}
