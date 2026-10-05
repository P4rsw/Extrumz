package com.music.bitchord

import com.music.bitchord.ui.screens.displayErrorDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard under the ChannelsScreen failure banner: whatever an exception
 * managed to put in `ScanState.lastErrorDetail`, the banner must either show
 * it cleanly or hide it — never crash on it, never let it grow unbounded
 * (stage 2.5, fix 2).
 */
class ScanErrorBannerTest {

    @Test
    fun `blank details stay hidden`() {
        assertNull(displayErrorDetail(null))
        assertNull(displayErrorDetail(""))
        assertNull(displayErrorDetail("   \n  "))
    }

    @Test
    fun `short details show as they are, trimmed`() {
        assertEquals("boom", displayErrorDetail("boom"))
        assertEquals("SQLITE_CORRUPT (11)", displayErrorDetail("  SQLITE_CORRUPT (11)  "))
    }

    @Test
    fun `details longer than 120 characters are cut with an ellipsis`() {
        val cut = displayErrorDetail("database disk image is malformed: ".repeat(30))
        assertNotNull(cut)
        assertEquals(121, cut!!.length) // 120 kept + the ellipsis
        assertTrue(cut.endsWith("…"))
        // Still carries the start of the real message.
        assertTrue(cut.startsWith("database disk image is malformed"))
    }

    @Test
    fun `exactly 120 characters are not cut`() {
        val exact = "x".repeat(120)
        assertEquals(exact, displayErrorDetail(exact))
    }
}
