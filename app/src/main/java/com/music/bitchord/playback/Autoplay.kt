package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * so the label and the behaviour are read from one place.
 */
fun autoplayEnabledFor(localPreference: Boolean): Boolean = localPreference

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * Loads one AutoPlay batch. The playback service is the only caller for the
 * AutoPlay toggle; the player UI's explicit radio start uses this same helper
 * for its initial station batch.
 *
 * Always an empty batch now: the related-tracks request it used to make was a
 * YouTube Music call, and nothing else in the app can suggest what comes after
 * a track. Callers already treat an empty batch as "nothing to add" — the radio
 * start reports that it could not load tracks and leaves the queue alone.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
): Result<List<Song>> = Result.success(emptyList())
