package com.music.bitchord

import com.music.bitchord.data.telegram.ExternalCovers
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier 3/4 sleeve storage: the player's large ask (itl_, ~1000px) must land
 * in a file of its own — an already-fetched 400px row sleeve answering as the
 * full-size one is exactly the blur the player upgrade exists to remove.
 */
class ExternalCoversTest {

    @Test
    fun rowLookupKeepsItsLegacyFileName() {
        assertEquals(
            "it_7_42.jpg",
            ExternalCovers.targetFile(File("/covers"), 7L, 42L, large = false).name,
        )
    }

    @Test
    fun largeLookupGetsItsOwnFile() {
        assertEquals(
            "itl_7_42.jpg",
            ExternalCovers.targetFile(File("/covers"), 7L, 42L, large = true).name,
        )
    }

    @Test
    fun itunesArtSwapsTheSizeToken() {
        val raw = "https://is1-ssl.mzstatic.com/image/thumb/Music123/abc/100x100bb.jpg"
        assertEquals(
            raw.replace("100x100bb.jpg", "400x400bb.jpg"),
            ExternalCovers.itunesArt(raw, large = false),
        )
        assertEquals(
            raw.replace("100x100bb.jpg", "1000x1000bb.jpg"),
            ExternalCovers.itunesArt(raw, large = true),
        )
    }
}
