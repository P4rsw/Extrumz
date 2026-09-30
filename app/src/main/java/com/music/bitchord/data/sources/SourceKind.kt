package com.music.bitchord.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose. **[rank] is the order sources are tried** — see
 * `SourceRegistry.active()`, which sorts on it — so the listener's own Telegram
 * library is asked first and YouTube Music last. Adding a source means adding
 * a [MusicSource] implementation and an entry here, which is the point — every
 * protocol the app speaks is one someone can read in this repo, and a source
 * can't teach the app a new way to behave after it ships.
 *
 * What varies per *instance* — which config, whose library — is [SourceConfig].
 */
enum class SourceKind(
    val label: String,
    val detail: String,
    /** The chips under the name on the sources screen. */
    val labels: List<String>,
    /** Whether an instance needs a URL before it can do anything — and so needs an editor. */
    val needsServer: Boolean,
    /** Whether this kind can serve bit-exact audio when asked. */
    val canServeLossless: Boolean,
    /**
     * Whether this kind answers quickly enough to be worth asking *before* a
     * track is played, so its copy can be pinned and cached ahead of time.
     *
     * False for every kind this build ships: [YOUTUBE] is warmed ahead of time
     * through its own read-ahead in
     * [AudioCache][com.music.bitchord.playback.AudioCache], which speaks video
     * ids directly and needs no cross-source match to find the track, and
     * [TELEGRAM] reads from the local index rather than a remote catalogue.
     * The property stays because [SourceResolver.prefetchSubstitute] gates its
     * speculative lookup on it — it simply narrows to nothing today.
     */
    val worthPrefetching: Boolean = false,
    /**
     * Where this kind sits in the walk, low first.
     *
     * Separate from [ordinal] so the two survivors keep the positions the
     * source order was written around: Telegram ahead of YouTube, which is
     * what makes a Telegram row playable without falling through to a catalogue
     * that can guess the wrong recording.
     */
    val rank: Int,
) {
    /**
     * The listener's own Telegram library: chats, channels and groups scanned
     * for audio messages, indexed in Room and streamed through TDLib.
     *
     * Rank puts it ahead of YouTube — it is the reason this fork exists, and it
     * should be asked before a catalogue that can guess the wrong recording. No
     * server to configure: credentials are baked in and authorization happens on
     * the account screens. `health()` reports Rejected until the listener has
     * logged in, so an unauthorized install never stalls a source walk.
     */
    TELEGRAM(
        label = "Telegram",
        detail = "کانال‌ها و گروه‌های تلگرام شما — اسکن‌شده و آفلاین قابل پخش.",
        labels = listOf("تلگرام", "آفلاین"),
        needsServer = false,
        canServeLossless = false,
        rank = 1,
    ),

    /**
     * The source the app was built on, listed here so it always has a fixed
     * place: last, behind Telegram. It cannot be removed — see [SourceRegistry].
     * Nothing else in the app can supply a home feed, a radio station or a
     * related-tracks queue.
     */
    YOUTUBE(
        label = "YouTube Music",
        detail = "The full catalogue, at Opus up to about 171 kbps. Lossy — there is no " +
            "lossless rendition to ask for.",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        canServeLossless = false,
        rank = 3,
    ),
    ;
}
