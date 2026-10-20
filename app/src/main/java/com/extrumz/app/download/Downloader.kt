package com.extrumz.app.download

import com.extrumz.app.data.DebugLog as Log
import com.extrumz.app.data.Http
import com.extrumz.app.data.sources.StreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Pulls a resolved stream onto disk.
 *
 * One thing here is not obvious and is load-bearing:
 *
 *  - **The source's own headers.** A source hands them over alongside the URL
 *    — see [SourceStream.headers][com.extrumz.app.data.sources.SourceStream.headers]
 *    — and they are whatever that server binds its links to. Fetching with
 *    anything else is a 403, so they are sent exactly as they arrived.
 *
 * One GET, not ranges, and sequential rather than parallel: ranges exist to
 * defeat a host that paces a continuous response down to playback speed (see
 * [ChunkedDataSource][com.extrumz.app.playback.ChunkedDataSource]), and a
 * typical AAC track is four megabytes anyway — splitting it across connections
 * buys nothing a user could perceive and costs the temp files and reassembly
 * that a cancelled download would then have to clean up.
 */
object Downloader {

    private const val TAG = "Extrumz"

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Fetch all of [url] into [sink].
     *
     * @param onProgress called as bytes land, and only when the response stated
     *   a length to measure against — an unstated one leaves the progress
     *   indeterminate rather than dividing by zero.
     * @return how many bytes were written.
     */
    suspend fun fetchDirect(
        url: String,
        headers: Map<String, String>,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        // Telegram does not hand over a URL to fetch; it hands over a path to a
        // file TDLib already put on disk — see [TelegramSource.stream], which
        // returns `Uri.fromFile(...)` for both of its tiers. OkHttp has no file
        // scheme and answers the whole attempt with "Expected URL scheme
        // 'http' or 'https' but was file", which is what every Telegram download
        // was getting. The bytes are already here, so the job is a copy.
        if (isLocalFile(url)) return@withContext copyLocalFile(url, sink, onProgress)
        if (schemeOf(url) != null && schemeOf(url) != "http" && schemeOf(url) != "https") {
            error("Don't know how to fetch a '${schemeOf(url)}' address")
        }
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) error("Download failed (HTTP ${response.code})")
            val body = response.body ?: error("Download failed: empty response")
            val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            val source = body.byteStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L

            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                written += read
                if (total != null) onProgress(written, total)
            }
            sink.flush()

            // A stated length that didn't all arrive is the one failure the
            // pending-row dance cannot catch on its own: nothing threw, so
            // committing would publish a file that looks whole and stops
            // halfway through the song.
            if (total != null && written < total) {
                error("Download stopped at ${written}B of $total — try again")
            }
            if (written == 0L) error("Download failed: nothing was sent")
            Log.d(TAG, "fetched ${written}B directly")
            written
        }
    }

    /**
     * Verify that a source URL is a file in the container it advertised before
     * it is allowed to become a download route.
     *
     * Some source backends label an HLS master playlist as FLAC. Downloading
     * that text faithfully produces a tiny `.flac` which no player can open;
     * rejecting it here keeps such a URL from ever becoming a route.
     */
    suspend fun isDirectAudioFile(
        url: String,
        headers: Map<String, String>,
        format: StreamFormat,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("Range", "bytes=0-31")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) return@use false
                val type = response.header("Content-Type").orEmpty().lowercase()
                if ("mpegurl" in type || "vnd.apple.mpegurl" in type) return@use false
                val head = response.body?.byteStream()?.readNBytes(32) ?: return@use false
                if (head.startsWith("#EXTM3U".toByteArray())) return@use false
                when (format.codec?.lowercase()) {
                    "flac", "x-flac" -> head.startsWith("fLaC".toByteArray())
                    "m4a", "mp4", "alac" -> head.size >= 8 &&
                        head.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
                    else -> head.isNotEmpty()
                }
            }
        }.onFailure { Log.w(TAG, "could not probe source file: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Whether [url] names a file already on this device rather than one to be
     * requested over the network.
     *
     * Asked before OkHttp, which has no file scheme and turns the attempt into
     * an IllegalArgumentException the caller would report as a download
     * failure — a confusing label for a path that was never in doubt.
     */
    internal fun isLocalFile(url: String): Boolean = schemeOf(url) == "file"

    /**
     * The scheme of [url], lowercased, or null when it has none.
     *
     * Read off the string rather than through `java.net.URI`: a URI built for
     * a path with spaces or a `%` that is not an escape throws, and a track
     * title is exactly where such things come from.
     */
    internal fun schemeOf(url: String): String? {
        val end = url.indexOf("://")
        if (end <= 0) return null
        val start = url.take(end).indexOfLast { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' } + 1
        if (start >= end) return null
        return url.substring(start, end).lowercase()
    }

    /**
     * Copy an already-local file into [sink], byte for byte.
     *
     * No transcode and no inspection: what [TelegramSource] holds is the file
     * the listener's own chat sent, and the point of a download is to keep that
     * one. Cancellation is checked per buffer so a queue row that is dismissed
     * mid-copy stops rather than finishing.
     */
    private suspend fun copyLocalFile(
        url: String,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long {
        val path = url.removePrefix("file://")
        val file = java.io.File(java.net.URLDecoder.decode(path, "UTF-8"))
        if (!file.isFile || file.length() == 0L) {
            error("That file is no longer on the device — ${file.name}")
        }
        val total = file.length()
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        java.io.FileInputStream(file).use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, total)
            }
        }
        sink.flush()
        if (written == 0L) error("Copy failed: nothing was read")
        Log.d(TAG, "copied ${written}B from a local file")
        return written
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
