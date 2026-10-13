package com.extrumz.app.playback.smart

/**
 * Keeps Automix analysis on the canonical rendition.
 *
 * Playback may deliberately use a source substitute, but
 * its cache entry is not interchangeable with the plain recording that the
 * analysis fetcher owns. The plain video id is the cache key pinned to that
 * copy; suffixed keys (`#alt`, `#hifi`, ...) belong to substitutes or
 * upgrades.
 */
internal object AutomixAnalysisSource {
    const val OPUS_QUERY_PARAMETER = "automix_opus"

    fun isCanonicalRendition(videoId: String?, cacheKey: String): Boolean =
        videoId == null || cacheKey == videoId

    /**
     * A private playback URI for analysis-only reads.
     *
     * A normal `watch?v=` URI is allowed to reuse the source race already
     * pinned for audible playback. That is exactly the wrong thing for
     * Automix: its independently cached copy must always be the plain Opus
     * read, even while playback is a substitute or a lossless upgrade.
     */
    fun opusUri(videoId: String): String = "extrumz://watch?v=$videoId&$OPUS_QUERY_PARAMETER=1"
}
