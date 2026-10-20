package com.extrumz.app

import com.extrumz.app.playback.smart.AutomixAnalysisSource
import com.extrumz.app.playback.QualityUpgrade
import com.extrumz.app.data.sources.StreamFormat
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AutomixAnalysisSourceTest {
    @Test
    fun `analysis accepts only its canonical cache key`() {
        assertTrue(AutomixAnalysisSource.isCanonicalRendition("video", "video"))
        assertFalse(AutomixAnalysisSource.isCanonicalRendition("video", "video#alt"))
        assertFalse(AutomixAnalysisSource.isCanonicalRendition("video", "video#hifi"))
    }

    @Test
    fun `analysis URI asks the resolver to bypass playback substitutions`() {
        assertTrue(AutomixAnalysisSource.opusUri("video").contains("v=video&automix_opus=1"))
    }

    @Test
    fun `second quality upgrade gets a new rendition marker`() {
        val jio = QualityUpgrade.upgradedUri("extrumz://watch?v=video")
        val lossless = QualityUpgrade.upgradedUri(jio)
        assertTrue(jio.endsWith("q=hifi"))
        assertTrue(lossless.endsWith("q=hifi-2"))
    }
}
