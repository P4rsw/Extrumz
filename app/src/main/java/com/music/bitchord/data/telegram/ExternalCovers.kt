package com.music.bitchord.data.telegram

import android.net.Uri
import com.music.bitchord.data.TrackLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Tier 3 artwork: when a message's audio carries NO album cover on Telegram
 * (albumCoverThumbnail and albumCoverMinithumbnail both absent — files
 * uploaded without embedded art), the row would stay placeholder forever.
 * [lookup] asks two keyless catalogues — iTunes Search first, then Deezer's
 * public search — for the title/performer and stores the winning sleeve
 * under covers/it_<chat>_<msg>.jpg.
 *
 * Matching is fuzzy, not blind: every candidate is scored with a normalized
 * Levenshtein similarity against both the title and the artist and the best
 * one is kept only when it clears [MIN_SCORE] — that accepts real-world
 * spelling drift ("Khabe Amigh" vs "Khaabe Amigh" — a genuine hit iTunes
 * misses and Deezer has) while rejecting a same-word coincidence from a
 * different song.
 *
 * Everything here runs inside Covers.fetchOnce's key + semaphore gate on
 * Dispatchers.IO — one request per provider per track per miss-TTL, four in
 * flight max, and a file already on disk returns without any network.
 */
internal object ExternalCovers {

    private const val TAG = "ExternalCovers"

    /** Below this best-score the whole lookup stays a miss. */
    private const val MIN_SCORE = 0.82

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    private const val ITUNES =
        "https://itunes.apple.com/search?media=music&entity=song&limit=5&term="
    private const val DEEZER =
        "https://api.deezer.com/search?limit=5&q="

    private const val BITCHORD_UA = "BitChord/1.0 (Android cover lookup)"

    /** Deezer rejects anything that looks like a browser or Android app. */
    private const val NO_UA = ""

    /** [noUa]: Deezer's CDN answers HTTP 403 to any User-Agent — download without one. */
    private data class Candidate(val track: String, val artist: String, val art: String, val noUa: Boolean = false)

    /**
     * Returns a `file://` sleeve URL for (chatId, messageId), or null when
     * nothing cleared the score floor. The file name is stable across
     * sessions, so a second lookup for the same track never re-downloads.
     */
    fun lookup(coversDir: File, chatId: Long, messageId: Long, title: String?, artist: String?): String? {
        val t = title.orEmpty().trim()
        if (t.isEmpty()) return null
        val a = artist.orEmpty().trim()

        return runCatching {
            val target = File(coversDir, "it_${chatId}_$messageId.jpg")
            if (target.exists() && target.length() > 0) return Uri.fromFile(target).toString()

            val query = if (a.isNotEmpty()) "$a $t" else t
            val candidates = searchItunes(query) + searchDeezer(query)
            val best = candidates
                .map { it to score(t, a, it) }
                .maxByOrNull { it.second }
            if (best == null || best.second < MIN_SCORE) {
                TrackLog.w(TAG, "lookup($chatId/$messageId) no candidate cleared " +
                    "score $MIN_SCORE (${candidates.size} seen) for '$query'")
                return null
            }
            val bytes = httpGetBytes(best.first.art, if (best.first.noUa) NO_UA else BITCHORD_UA) ?: return null
            if (bytes.isEmpty()) return null
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            TrackLog.w(TAG, "lookup($chatId/$messageId) -> ${best.first.artist} – " +
                "${best.first.track} (score ${"%.2f".format(best.second)})")
            Uri.fromFile(target).toString()
        }.onFailure { e ->
            TrackLog.w(TAG, "lookup($chatId/$messageId) failed: ${e::class.simpleName}: ${e.message}")
        }.getOrNull()
    }

    // ---------------------------------------------------------------- providers

    private fun searchItunes(term: String): List<Candidate> {
        val body = try {
            httpGetString(ITUNES + Uri.encode(term), BITCHORD_UA)
        } catch (e: Exception) {
            TrackLog.w(TAG, "itunes '$term' threw: ${e::class.simpleName}: ${e.message}")
            return emptyList()
        }
        if (body == null) {
            return emptyList()
        }
        val results = JSONObject(body).optJSONArray("results")
        if (results == null) return emptyList()
        return buildList {
            for (i in 0 until minOf(results.length(), 5)) {
                val r = results.optJSONObject(i) ?: continue
                add(
                    Candidate(
                        track = r.optString("trackName"),
                        artist = r.optString("artistName"),
                        art = r.optString("artworkUrl100")
                            .replace("100x100bb.jpg", "400x400bb.jpg"),
                    )
                )
            }
        }
    }

    private fun searchDeezer(term: String): List<Candidate> {
        val body = try {
            httpGetString(DEEZER + Uri.encode(term), NO_UA)
        } catch (e: Exception) {
            TrackLog.w(TAG, "deezer '$term' threw: ${e::class.simpleName}: ${e.message}")
            return emptyList()
        }
        if (body == null) {
            return emptyList()
        }
        val data = JSONObject(body).optJSONArray("data")
        if (data == null) return emptyList()
        return buildList {
            for (i in 0 until minOf(data.length(), 5)) {
                val r = data.optJSONObject(i) ?: continue
                val cover = r.optJSONObject("album") ?: continue
                // cover_big (500px) when present, cover_medium (250px) otherwise.
                val art = cover.optString("cover_big").ifEmpty { cover.optString("cover_medium") }
                if (art.isEmpty()) continue
                add(
                    Candidate(
                        track = r.optString("title"),
                        artist = r.optJSONObject("artist")?.optString("name").orEmpty(),
                        art = art,
                        noUa = true,
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------- matching

    /** Best of the title and artist similarity, each on normalized text. */
    private fun score(title: String, artist: String, c: Candidate): Double {
        val nt = norm(title); val rt = norm(c.track)
        val na = norm(artist); val ra = norm(c.artist)
        val titleSim = if (nt.length >= 3 && rt.length >= 3) similarity(nt, rt) else 0.0
        val artistSim = if (na.length >= 2 && ra.length >= 2) similarity(na, ra) else 0.0
        return maxOf(titleSim, artistSim)
    }

    /** 1.0 = identical, 0.0 = nothing in common, in between by edit distance. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, b.length + 1)
        }
        return prev[b.length]
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06ff]+"), "")

    // ---------------------------------------------------------------- http

    private fun httpGetString(url: String, userAgent: String?): String? =
        httpGetBytes(url, userAgent)?.toString(Charsets.UTF_8)

    private fun httpGetBytes(url: String, userAgent: String?): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            // Deezer silently returns an EMPTY result set for browser/app-like
            // User-Agents (Dalvik, Mozilla, BitChord) and only honours requests
            // with no UA or a tool-like one — verified with adb curl on device.
            // An empty header value clears Android's default "Dalvik/..." UA.
            conn.setRequestProperty("User-Agent", userAgent.orEmpty())
            if (conn.responseCode != 200) {
                TrackLog.d(TAG, "GET $url -> HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
