package com.extrumz.app.data.telegram

import com.extrumz.app.data.db.entity.Track

/**
 * What "the same song" means for F7's dedupe.
 *
 * The old fingerprint was `lowercase(performer) | lowercase(title) | duration`
 * over the raw strings, which failed in BOTH directions on this corpus:
 *
 *  - UNDER-merge: `"Arman Miladi, Hoomaan, Arown"` and
 *    `"Arman Miladi & Hoomaan & Arown"` are the same three credits with a
 *    different separator, but the raw strings differ — so one bot channel
 *    ended up with two rows of the same song.
 *
 *  - OVER-merge risk: comparing credits as a subset would fold a 4-credit
 *    version into a 3-credit one. An extra credited artist almost always
 *    means a remix or a guest verse, and merging would make the song
 *    disappear when browsing by the artist that got dropped. So credits are
 *    compared as SETS (order and separator insensitive), never as subsets.
 *
 * Titles carry the region's noise too: bitrate tags the bots append, and
 * Arabic-letter lookalikes that differ from their Persian forms by a single
 * codepoint.
 */
object TrackFingerprinter {

    /** One credit, split away from the delimiters Telegram bots use. */
    private val CREDIT_SPLIT = Regex(
        """\s*(?:[,،&+/\\|]|(?:\s|^)(?:feat|ft|featuring|with)\.?\s+)\s*|\s+x\s+""",
        RegexOption.IGNORE_CASE,
    )

    /** `"Mohem Ni Vasam (320)"`, `"- 128"`, `"(Official Audio)"` … */
    private val TITLE_NOISE = Regex(
        """\s*[\(\[\-–]\s*(?:\d{2,3}\s*kbps?|128|320|flac|mp3|wav|audio|""" +
            """official\s+audio|official|original|hq|hd|full)\s*[\)\]]?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Anything that is not a letter or a digit becomes a single space, so
     * punctuation differences (`'` vs `’`, `-` vs `–`) stop splitting titles.
     * Keeps Persian/Arabic letters — only ASCII-only normalization would
     * erase the entire Persian half of this library.
     */
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Arabic Yeh/Kaf → Persian, and the zero-width non-joiner stripped.
     * The two yehs are the classic false negative here: `علي` and `علی` are
     * the same name typed on different keyboards.
     */
    fun normalizeScript(text: String): String = text
        .replace('\u064A', '\u06CC') // Arabic Yeh  ي → Persian Yeh  ی
        .replace('\u0643', '\u06A9') // Arabic Kaf  ك → Persian Kaf  ک
        .replace("\u200C", "")        // ZWNJ — invisible, splits words
        .replace("\u200E", "")        // LRM
        .replace("\u200F", "")        // RLM
        .trim()

    /** Credits as an order-insensitive set: separators and order are noise. */
    fun normalizePerformer(raw: String): String {
        val clean = normalizeScript(raw)
        if (clean.isBlank()) return ""
        return clean.split(CREDIT_SPLIT)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("|")
    }

    fun normalizeTitle(raw: String): String {
        val clean = normalizeScript(raw)
        val stripped = TITLE_NOISE.replace(clean, "")
        // Whitespace is dropped entirely at the end, not folded to one space.
        // The ZWNJ above is removed while a typed space survives, so
        // `دل‌تنگ` (ZWNJ) and `دل تنگ` (space) would otherwise come out as
        // `دلتنگ` and `دل تنگ` — the same title split by a typographic
        // choice. Fingerprints compare identity, never display, so spacing
        // is not part of the key.
        return stripped.lowercase().replace(NON_WORD, " ").replace(" ", "")
    }

    /**
     * The dedupe key. `duration` stays in it because a bare title like
     * `"Intro"` collides across artists constantly, and Telegram reports the
     * length to the second — a real repost keeps it, a different recording
     * does not.
     */
    fun fingerprintOf(track: Track): Fingerprint =
        fingerprintOf(track.performer, track.title, track.duration)

    /**
     * The raw-column entry point: [LibraryScanner]'s light index reads
     * performer/title/duration straight off a projection row rather than
     * materialising a whole [Track], so the key must not demand one.
     */
    fun fingerprintOf(performer: String, title: String, duration: Int): Fingerprint =
        Fingerprint(
            performer = normalizePerformer(performer),
            title = normalizeTitle(title),
            duration = duration,
        )

    data class Fingerprint(
        val performer: String,
        val title: String,
        val duration: Int,
    )
}
