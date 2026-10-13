package com.extrumz.app.data.telegram

import android.net.Uri
import com.extrumz.app.data.TrackLog
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
 * Two callers, two sizes: a row's tier-3 fills an EMPTY cell with the 400 px
 * sleeve under it_, while the player's tier-4 ([Covers.upgradePlayerCover],
 * large=true) asks for ~1000 px under itl_ — a separate file, so a row sleeve
 * already on disk can never pass itself off as the full-size one.
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

    private const val EXTRUMZ_UA = "Extrumz/1.0 (Android cover lookup)"

    /** Deezer's API rejects anything that looks like a browser or Android app. */
    private const val NO_UA = ""

    /**
     * dzcdn (Deezer's image CDN) is the OPPOSITE of its API: it answers
     * HTTP 403 to empty, tool and app User-Agents — verified against
     * cdn-images.dzcdn.net (empty → 403, browser → 200) — and downloads only
     * with a full browser UA. The API keeps [NO_UA].
     */
    private const val BROWSER_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** [deezerCdn]: the art URL lives on dzcdn and must download as a browser. */
    private data class Candidate(val track: String, val artist: String, val art: String, val deezerCdn: Boolean = false)

    /**
     * Returns a `file://` sleeve URL for (chatId, messageId), or null when
     * nothing cleared the score floor. The file name is stable across
     * sessions, so a second lookup for the same track never re-downloads.
     */
    fun lookup(
        coversDir: File,
        chatId: Long,
        messageId: Long,
        title: String?,
        artist: String?,
        large: Boolean = false,
    ): String? {
        val t = title.orEmpty().trim()
        if (t.isEmpty()) return null
        val a = artist.orEmpty().trim()

        return runCatching {
            val target = targetFile(coversDir, chatId, messageId, large)
            if (target.exists() && target.length() > 0) return Uri.fromFile(target).toString()

            val query = if (a.isNotEmpty()) "$a $t" else t
            val candidates = searchItunes(query, large) + searchDeezer(query, large)
            val best = candidates
                .map { it to score(t, a, it) }
                .maxByOrNull { it.second }
            if (best == null || best.second < MIN_SCORE) {
                TrackLog.w(TAG, "lookup($chatId/$messageId) no candidate cleared " +
                    "score $MIN_SCORE (${candidates.size} seen) for '$query'")
                return null
            }
            val ua = if (best.first.deezerCdn) BROWSER_UA else EXTRUMZ_UA
            val bytes = httpGetBytes(best.first.art, ua)
            if (bytes == null || bytes.isEmpty()) {
                // Previously a bare `return null`, which hid every failed
                // image fetch behind the D-level GET line — tier-4 then
                // looked like it had never run.
                TrackLog.w(TAG, "lookup${if (large) "[large]" else ""}($chatId/$messageId) art download failed: ${best.first.art}")
                return null
            }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            TrackLog.w(TAG, "lookup${if (large) "[large]" else ""}($chatId/$messageId) -> ${best.first.artist} – " +
                "${best.first.track} (score ${"%.2f".format(best.second)})")
            Uri.fromFile(target).toString()
        }.onFailure { e ->
            TrackLog.w(TAG, "lookup($chatId/$messageId) failed: ${e::class.simpleName}: ${e.message}")
        }.getOrNull()
    }

    // ---------------------------------------------------------------- providers

    /** Where a lookup's sleeve lands; itl_/it_ keep the two sizes apart. */
    internal fun targetFile(coversDir: File, chatId: Long, messageId: Long, large: Boolean): File =
        File(coversDir, (if (large) "itl_" else "it_") + "${chatId}_$messageId.jpg")

    /** iTunes serves every size off the same URL — swap the size token. */
    internal fun itunesArt(artworkUrl100: String, large: Boolean): String =
        artworkUrl100.replace("100x100bb.jpg", if (large) "1000x1000bb.jpg" else "400x400bb.jpg")

    private fun searchItunes(term: String, large: Boolean): List<Candidate> {
        val body = try {
            httpGetString(ITUNES + Uri.encode(term), EXTRUMZ_UA)
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
                        art = itunesArt(r.optString("artworkUrl100"), large),
                    )
                )
            }
        }
    }

    private fun searchDeezer(term: String, large: Boolean): List<Candidate> {
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
                // large → cover_xl (1000px) through cover_big (500px); the row's
                // ask takes cover_big (500px) through cover_medium (250px) —
                // enough for a thumbnail, not for a full-screen hero.
                val art = if (large) {
                    cover.optString("cover_xl").ifEmpty { cover.optString("cover_big") }
                } else {
                    cover.optString("cover_big").ifEmpty { cover.optString("cover_medium") }
                }
                if (art.isEmpty()) continue
                add(
                    Candidate(
                        track = r.optString("title"),
                        artist = r.optJSONObject("artist")?.optString("name").orEmpty(),
                        art = art,
                        deezerCdn = true,
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
            // Deezer's API silently returns an EMPTY result set for
            // browser/app-like User-Agents (Dalvik, Mozilla, Extrumz) and
            // only honours requests with no UA — verified with adb curl on
            // device. Its image CDN wants the exact opposite (BROWSER_UA);
            // the caller picks per host. An empty header value clears
            // Android's default "Dalvik/..." UA.
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
