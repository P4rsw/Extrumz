package com.music.bitchord.data.telegram

import java.io.File
import java.io.RandomAccessFile

/**
 * Tier 0 of the cover upgrade: the artwork the release itself carries,
 * embedded in the audio file's own header — ID3v2 APIC for MP3, FLAC
 * PICTURE block for FLAC, MP3 pictures are overwhelmingly what Telegram
 * music messages hold.
 *
 * Why this exists: a channel can post a title with no cover, so the row
 * keeps the message's ~90 px inline thumbnail and the player scales it 5x
 * into a blur — while the SAME bytes being played already carry the
 * artist's real sleeve at full size. Reading it costs one header read of
 * a file that is on disk anyway: no network, no catalogue guesswork, and
 * by construction the picture cannot belong to a different track.
 *
 * Parsing rules, each load-bearing:
 * - Only the first [MAX_HEADER_BYTES] are read — the tag holds the art and
 *   little else, and an unbounded read is an unbounded heap.
 * - Tag-level unsynchronisation or an extended header means every offset
 *   in the tag describes bytes that are not on disk; those tags are
 *   refused rather than misparsed.
 * - Compressed, encrypted, grouped or per-frame-unsynchronised frames are
 *   skipped, never guessed at: their bytes are not a picture.
 * - The winner is the LARGEST picture by bytes — multi-APIC tags (front
 *   cover plus a tiny icon) must not resolve to the icon.
 * - Only JPEG/PNG/WebP MAGIC passes: the mime string in an APIC frame is
 *   publisher text, the magic is the file. A wrong extension would make
 *   BitmapFactory fail the row that would otherwise have been fine.
 */
internal object EmbeddedArt {

    /** One extracted picture, ready to persist. Bytes are raw as stored. */
    data class Image(val bytes: ByteArray, val mimeType: String)

    /**
     * Cap on the header region read. The MP3 tag before this cap already
     * covers the 533 KB Mohreh tag in full; anything past it is an outlier
     * that is not worth megabytes of phone heap for a cover.
     */
    private const val MAX_HEADER_BYTES = 4 * 1024 * 1024

    /**
     * Largest embedded picture in [file], or null when the file is not
     * there yet, carries no picture, or every candidate is truncated
     * (a still-downloading copy: the frame size runs past the bytes on
     * disk, so the parse fails instead of writing a stub image — the next
     * play finds the finished file and succeeds).
     */
    fun largest(file: File): Image? {
        if (!file.isFile) return null
        val length = file.length()
        if (length < 64L) return null
        val buf = ByteArray(minOf(length, MAX_HEADER_BYTES.toLong()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(buf) }
        val image = when {
            isId3(buf) -> largestId3(buf)
            isFlac(buf) -> largestFlac(buf)
            else -> null
        } ?: return null
        return if (looksLikeImage(image.bytes)) image else null
    }

    private fun looksLikeImage(b: ByteArray): Boolean =
        b.size > 12 &&
            ((b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || // JPEG
                (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && // PNG
                    b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()) ||
                (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && // WebP
                    b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
                    b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()))

    private fun mimeOf(b: ByteArray): String = when {
        b[0] == 0x89.toByte() -> "image/png"
        b[0] == 'R'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    // ---------------------------------------------------------------- ID3v2

    private fun isId3(b: ByteArray): Boolean =
        b.size >= 10 && b[0] == 'I'.code.toByte() &&
            b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun largestId3(b: ByteArray): Image? {
        val version = b[3].toInt() and 0xFF // 2, 3 or 4
        if (version !in 2..4) return null
        val tagFlags = b[4].toInt() and 0xFF
        if (tagFlags and 0x40 != 0) return null // extended header: sizes get ambiguous
        if (tagFlags and 0x80 != 0) return null // unsynchronisation: offsets stop matching disk
        val tagEnd = minOf(10 + syncsafe(b, 6), b.size)
        val frameHeader = if (version == 2) 6 else 10
        var p = 10
        var best: ByteArray? = null
        while (p + frameHeader <= tagEnd) {
            val idLen = if (version == 2) 3 else 4
            val id = ascii(b, p, idLen)
            if (id.isEmpty() || id[0] == '\u0000') break // padding starts
            val size: Int
            val frameFlags: Int
            if (version == 2) {
                size = be24(b, p + 3)
                frameFlags = 0
            } else {
                size = if (version == 4) syncsafe(b, p + 4) else be32(b, p + 4)
                frameFlags = ((b[p + 8].toInt() and 0xFF) shl 8) or (b[p + 9].toInt() and 0xFF)
            }
            val body = p + frameHeader
            if (size <= 0 || body + size > tagEnd) break // truncated frame: file still downloading
            if ((id == "APIC" || id == "PIC") && frameIsPlain(frameFlags, version)) {
                val pic = parseApic(b, body, body + size, version)
                if (pic != null && (best == null || pic.size > best.size)) best = pic
            }
            p = body + size
        }
        return best?.let { Image(it, mimeOf(it)) }
    }

    /** False when the frame is compressed/encrypted/grouped/unsynced — not a raw picture. */
    private fun frameIsPlain(frameFlags: Int, version: Int): Boolean = when (version) {
        2 -> true // v2.2 frames carry no flags
        3 -> frameFlags and 0xE0 == 0 // 0x80 compress, 0x40 encrypt, 0x20 group
        else -> frameFlags and 0x4F == 0 // 0x0C format, 0x02 unsync, 0x01 length indicator, 0x40 group
    }

    /** APIC body: enc, (mime + image-format), picType, description, picture. */
    private fun parseApic(b: ByteArray, start: Int, end: Int, version: Int): ByteArray? {
        if (start >= end) return null
        var p = start
        val enc = b[p].toInt() and 0xFF
        if (enc !in 0..3) return null
        p++
        if (version == 2) {
            if (p + 3 > end) return null
            p += 3 // 3-char image format (JPG/PNG/PIC) — the magic decides later
        } else {
            while (p < end && b[p].toInt() != 0) p++ // ASCII mime
            if (p >= end) return null
            p++ // the terminator
        }
        if (p >= end) return null
        p++ // picture type byte — decorative
        p = descEnd(b, p, end, enc) ?: return null
        if (p >= end) return null
        return b.copyOfRange(p, end)
    }

    /** Index just past the description's terminator; null when unterminated. */
    private fun descEnd(b: ByteArray, start: Int, end: Int, enc: Int): Int? {
        if (enc == 1 || enc == 2) {
            var p = start
            while (p + 1 < end) { // UTF-16: 0x0000 on an even pair boundary
                if (b[p].toInt() == 0 && b[p + 1].toInt() == 0) return p + 2
                p += 2
            }
            return null
        }
        var p = start
        while (p < end) { // byte terminator: enc 0 (latin1) or 3 (utf-8)
            if (b[p].toInt() == 0) return p + 1
            p++
        }
        return null
    }

    // ------------------------------------------------------------------ FLAC

    private fun isFlac(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 'f'.code.toByte() && b[1] == 'l'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    private fun largestFlac(b: ByteArray): Image? {
        var p = 4
        var best: ByteArray? = null
        while (p + 4 <= b.size) {
            val header = b[p].toInt() and 0xFF
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val len = be24(b, p + 1)
            val body = p + 4
            if (len < 0 || body + len > b.size) break // truncated block
            if (type == 6) { // PICTURE
                val pic = parseFlacPicture(b, body, body + len)
                if (pic != null && (best == null || pic.size > best.size)) best = pic
            }
            if (last) break
            p = body + len
        }
        return best?.let { Image(it, mimeOf(it)) }
    }

    /** PICTURE block: type, mime, description, 4x int32, data length, data. */
    private fun parseFlacPicture(b: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        if (p + 4 > end) return null
        p += 4 // picture type
        while (p < end && b[p].toInt() != 0) p++ // mime
        if (p >= end) return null
        p++
        while (p < end && b[p].toInt() != 0) p++ // description
        if (p >= end) return null
        p++
        if (p + 20 > end) return null
        p += 16 // width, height, depth, colors
        val dataLen = be32(b, p)
        p += 4
        if (dataLen <= 0 || p + dataLen > end) return null
        return b.copyOfRange(p, p + dataLen)
    }

    // ---------------------------------------------------------------- helpers

    private fun syncsafe(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0x7F) shl 21) or
            ((b[p + 1].toInt() and 0x7F) shl 14) or
            ((b[p + 2].toInt() and 0x7F) shl 7) or
            (b[p + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or
            ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or
            (b[p + 3].toInt() and 0xFF)

    private fun be24(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 16) or
            ((b[p + 1].toInt() and 0xFF) shl 8) or
            (b[p + 2].toInt() and 0xFF)

    private fun ascii(b: ByteArray, p: Int, len: Int): String {
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append((b[p + i].toInt() and 0xFF).toChar())
        return sb.toString()
    }
}
