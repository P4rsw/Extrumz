package com.music.bitchord.data.stats

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the Replay knows about an artist beyond their name: their picture and
 * their page.
 *
 * ## Why any of this needed looking up
 *
 * Listening is counted per *name*, because a name is the only thing every track
 * carries — see [ListeningStats]. That is enough to rank artists and nothing
 * else. Two things the artist chart wants are simply not in the listening:
 *
 *  - **A picture of the artist.** Every track carries its own sleeve, and using
 *    that gave an artist chart illustrated with album covers — the same cover as
 *    the song chart above it, which reads as the page having drawn the wrong
 *    list rather than as a deliberate choice.
 *  - **A page to open.** A browse id only rides along when the row that queued
 *    the track happened to have one, which for a home-feed card it does not.
 *
 * ## Where they come from now
 *
 * Neither can be looked up any more — the artist search this used to make was a
 * YouTube Music call and is gone. What is here is only what a previous session
 * wrote down: the store is read once at launch, never asked again, and an artist
 * nobody has a record of simply keeps the track's sleeve.
 */
object ArtistFacts {

    private lateinit var file: File

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** artist (lowercased) → what is known about them. */
    private val known = ConcurrentHashMap<String, StoredArtist>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Bumped whenever a lookup lands.
     *
     * The Replay reads these facts once, while it is building its charts, so a
     * picture that arrives a second after the page opened would otherwise not
     * appear until the page was opened again — which for a page opened a few
     * times a year means never. Watching this lets it rebuild; with no lookups
     * left it simply never fires, which is the correct answer for a store that
     * only ever grows on disk between launches.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        scope.launch { load() }
    }

    private val ready: Boolean get() = this::file.isInitialized

    // ── Reading ─────────────────────────────────────────────────────────────
    //
    // None of these reach the network. The Replay is drawn from what is already
    // known, and an artist that isn't yet simply keeps the track's sleeve.
    // Asking here would put a round trip per artist behind a page that opens
    // with fifty of them on it.

    /** A picture of the artist, or null to fall back to whatever the row has. */
    fun imageFor(artist: String): String? = known[key(artist)]?.image

    /** The artist's page, so a chart row opens it without searching first. */
    fun browseIdFor(artist: String): String? = known[key(artist)]?.browseId

    private fun key(artist: String): String = artist.trim().lowercase(Locale.ROOT)

    // ── Persistence ─────────────────────────────────────────────────────────
    //
    // Read-only in place: nothing writes new facts, so the file written by an
    // earlier build stays exactly as it was and the save timer is gone with it.

    private fun load() {
        if (!ready || !file.exists()) return
        runCatching {
            json.decodeFromString(Stored.serializer(), file.readText())
        }.onSuccess { stored ->
            stored.artists.forEach { known[it.key] = it }
        }.onFailure {
            Log.w(TAG, "Discarding unreadable artist cache", it)
            file.delete()
        }
    }

    @Serializable
    private data class Stored(val version: Int = 2, val artists: List<StoredArtist> = emptyList())

    @Serializable
    data class StoredArtist(
        val key: String,
        val image: String? = null,
        val browseId: String? = null,
        val cardAt: Long = 0L,
    )

    private const val TAG = "BitChordArtists"
    private const val FILE_NAME = "artist_facts.json"

}
