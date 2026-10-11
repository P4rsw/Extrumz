package com.music.bitchord

import com.music.bitchord.download.Downloader
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [Downloader] does with an address that is not a URL to fetch.
 *
 * This exists because the download path only ever saw http(s) until Telegram
 * started being downloadable at all: [com.music.bitchord.data.sources.TelegramSource]
 * returns a *path* — TDLib has already put the file on disk — and OkHttp has no
 * file scheme, so every Telegram download died with "Expected URL scheme
 * 'http' or 'https' but was file". A copy, not a request.
 */
class DownloaderTest {

    @Test
    fun `a local file is recognised as local`() {
        assertTrue(Downloader.isLocalFile("file:///data/user/0/app/files/a.mp3"))
        assertFalse(Downloader.isLocalFile("https://cdn.example/a.mp3"))
        assertFalse(Downloader.isLocalFile("http://cdn.example/a.mp3"))
        assertFalse(Downloader.isLocalFile("/just/a/path.mp3"))
        assertFalse(Downloader.isLocalFile(""))
    }

    /**
     * Read off the string rather than through [java.net.URI], because a URI
     * built from a title containing a `%` or a space throws — and those are
     * exactly the titles a music library is made of.
     */
    @Test
    fun `the scheme is read without a uri parser`() {
        assertEquals("https", Downloader.schemeOf("https://cdn.example/a.mp3"))
        assertEquals("http", Downloader.schemeOf("http://cdn.example/a.mp3"))
        assertEquals("file", Downloader.schemeOf("file:///storage/emulated/0/a.mp3"))
        assertEquals("bitchord", Downloader.schemeOf("bitchord://watch?v=abc"))
        assertNull(Downloader.schemeOf("/plain/path.mp3"))
        assertNull(Downloader.schemeOf(""))
        // A title with characters a URI parser would object to.
        assertEquals("file", Downloader.schemeOf("file:///music/100% Khas.mp3"))
    }

    /**
     * The whole point: bytes already on the device reach the sink unchanged.
     * A local copy that silently truncated would publish a file that looks
     * whole and stops halfway through the song.
     */
    @Test
    fun `a local file is copied byte for byte`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("downloader-test").toFile()
        try {
            val source = File(dir, "track.flac")
            val payload = ByteArray(200_000) { (it % 251).toByte() }
            source.writeBytes(payload)

            val sink = ByteArrayOutputStream()
            var lastProgress = 0L
            val written = Downloader.fetchDirect(
                url = "file://" + source.absolutePath,
                headers = emptyMap(),
                sink = sink,
                onProgress = { w, _ -> lastProgress = w },
            )

            assertEquals(payload.size.toLong(), written)
            assertTrue(payload.contentEquals(sink.toByteArray()))
            assertEquals(payload.size.toLong(), lastProgress)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A file that has gone is a sentence, not a stack trace. */
    @Test
    fun `a file that is no longer there says so`() = runBlocking {
        val sink = ByteArrayOutputStream()
        val message = runCatching {
            Downloader.fetchDirect(
                url = "file:///definitely/not/here.mp3",
                headers = emptyMap(),
                sink = sink,
                onProgress = { _, _ -> },
            )
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("got: $message", "no longer on the device" in message)
    }

    /** An address this cannot fetch is named, rather than handed to OkHttp. */
    @Test
    fun `an unknown scheme is refused by name`() = runBlocking {
        val message = runCatching {
            Downloader.fetchDirect(
                url = "ftp://example.com/a.mp3",
                headers = emptyMap(),
                sink = ByteArrayOutputStream(),
                onProgress = { _, _ -> },
            )
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("got: $message", "ftp" in message)
    }
}
