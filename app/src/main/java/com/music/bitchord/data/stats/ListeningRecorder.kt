package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlin.math.min

/**
 * Turns the player's ticking into the numbers [ListeningStats] keeps.
 *
 * ## Why it samples rather than measures
 *
 * The tempting implementation reads the track's position when it ends and calls
 * that the listening. It is wrong in every direction that matters: a track
 * skipped at 0:20 reports twenty seconds it did play, a track seeked back over
 * reports less than it played, a track left paused for an hour reports the same
 * as one played through, and a track the process is killed under reports
 * nothing at all.
 *
 * So this is fed from the service's existing progress sampler, which only ticks
 * while audio is actually coming out, and each tick contributes the *wall-clock*
 * time since the last one. Seeking cannot inflate it because position is never
 * consulted. Pausing cannot inflate it because no tick happens. The gap either
 * side of a pause is capped at [MAX_STEP_MS], which is what stops an hour on the
 * lock screen from arriving as an hour of listening on the first tick after
 * resume.
 *
 * The cost is that the few seconds before the first tick of each track are not
 * counted. That is a systematic undercount of at most one sample interval per
 * track, and it is the right way to be wrong: every alternative overcounts, and
 * a Replay that flatters is worth less than one that is a little shy.
 *
 * ## Minutes and plays are different questions
 *
 * Minutes accumulate continuously. A *play* is counted once, when enough of the
 * track has gone by to call it listened to — a half-or-four-minutes rule held
 * here, so a play means the same thing wherever it is reported.
 */
object ListeningRecorder {

    private var currentId: String? = null
    private var lastSampleAt: Long = 0L
    private var playedThisTrack: Long = 0L
    private var playCounted = false
    private var samplesSinceFlush = 0

    /**
     * One tick of the player's sampler.
     *
     * [durationMs] is the decoder's figure when it has one; the row's stated
     * runtime stands in until it does, and a track with neither simply has to
     * clear the thirty-second floor to count as a play.
     */
    @Synchronized
    fun onSample(song: Song, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (song.videoId != currentId) {
            // A new track anchors the clock and contributes nothing yet — see
            // the class note on undercounting.
            currentId = song.videoId
            lastSampleAt = now
            playedThisTrack = 0L
            playCounted = false
            return
        }
        val step = (now - lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        lastSampleAt = now
        if (step <= 0L) return
        playedThisTrack += step

        val length = durationMs.takeIf { it > 0 } ?: song.durationMillis()
        val threshold = if (length > 0) {
            min(length / 2, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS)
        } else {
            PLAY_FLOOR_MS
        }
        val counts = !playCounted && playedThisTrack >= threshold
        if (counts) playCounted = true

        ListeningStats.record(song, step, counts)

        if (++samplesSinceFlush >= FLUSH_EVERY) {
            samplesSinceFlush = 0
            ListeningStats.flush()
        }
    }

    /**
     * Playback stopped, paused, or moved on.
     *
     * Forgetting the current track is what makes the *next* tick anchor rather
     * than contribute: without it, a player paused for an afternoon would hand
     * [MAX_STEP_MS] of listening to whatever was on screen when it resumed.
     */
    @Synchronized
    fun onStopped() {
        currentId = null
        playedThisTrack = 0L
        playCounted = false
        samplesSinceFlush = 0
        ListeningStats.flush()
    }

    // ── What the queue didn't carry ─────────────────────────────────────────
    //
    // Nothing is looked up any more. A row off a feed or an AutoPlay suggestion
    // may arrive without an album, and it is simply recorded without one — the
    // alternative was a request per track to a service this app no longer
    // talks to.

    /**
     * The most a single tick may contribute.
     *
     * A shade over the sampler's interval, so an ordinary tick that arrived late
     * — a busy main thread, a device coming out of doze — is still counted in
     * full, while the unbounded gap across a pause is not.
     */
    private const val MAX_STEP_MS = 8_000L

    /** Under half a minute is not a listen, however it ended. */
    private const val PLAY_FLOOR_MS = 30_000L

    /** Past four minutes, half a track is more listening than anyone disputes. */
    private const val PLAY_CEILING_MS = 4 * 60 * 1000L

    /** Six ticks — about half a minute of listening between disk writes. */
    private const val FLUSH_EVERY = 6
}
