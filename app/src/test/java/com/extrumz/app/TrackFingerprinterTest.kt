package com.extrumz.app

import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.telegram.TrackFingerprinter
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/**
 * The two production failures this normalizer exists for, plus the case that
 * must NOT merge (an extra credit = a different version of the song).
 */
class TrackFingerprinterTest {

    private fun track(performer: String, title: String, duration: Int = 200) =
        Track(
            id = 0,
            fileUniqueId = "x",
            fileId = "y",
            performer = performer,
            title = title,
            duration = duration,
        )

    @Test
    fun `same credits with different separators share one fingerprint`() {
        // Observed in Music Downloader: two rows of the same song, one with
        // commas, one with ampersands — F7 never merged them.
        val a = TrackFingerprinter.fingerprintOf(
            track("Arman Miladi, Hoomaan, Arown", "Mohem Ni Vasam"),
        )
        val b = TrackFingerprinter.fingerprintOf(
            track("Arman Miladi & Hoomaan & Arown", "Mohem Ni Vasam"),
        )
        assertEquals("separator and order must not split one song", a, b)
    }

    @Test
    fun `an extra credit is a different version and must not merge`() {
        // Dropping Koorosh here would make the song vanish when browsing by
        // that artist, so set equality is the limit — never subset matching.
        val three = TrackFingerprinter.fingerprintOf(
            track("Arman Miladi, Hoomaan, Arown", "Freakam"),
        )
        val four = TrackFingerprinter.fingerprintOf(
            track("Arman Miladi, Hoomaan, Koorosh, Arown", "Freakam"),
        )
        assertNotEquals("a 4-credit version must survive separately", three, four)
    }

    @Test
    fun `arabic and persian letterforms are one word`() {
        // Same name typed on two keyboards: ي vs ی, ك vs ک.
        assertEquals(
            TrackFingerprinter.fingerprintOf(track("علي", "دل تنگ")),
            TrackFingerprinter.fingerprintOf(track("علی", "دل‌تنگ")),
        )
    }

    @Test
    fun `bots bitrate suffixes do not split a title`() {
        assertEquals(
            TrackFingerprinter.fingerprintOf(track("Hoomaan", "Mohem Ni Vasam (320)")),
            TrackFingerprinter.fingerprintOf(track("Hoomaan", "Mohem Ni Vasam - 128")),
        )
    }

    @Test
    fun `different durations never merge`() {
        assertNotEquals(
            TrackFingerprinter.fingerprintOf(track("Hoomaan", "Intro", 90)),
            TrackFingerprinter.fingerprintOf(track("Hoomaan", "Intro", 190)),
        )
    }
}
