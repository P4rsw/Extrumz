package com.music.bitchord.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose. **[rank] is the order sources are tried** — see
 * `SourceRegistry.active()`, which sorts on it — so the listener's own Telegram
 * library is asked first. Adding a source means adding a [MusicSource]
 * implementation and an entry here, which is the point — every protocol the
 * app speaks is one someone can read in this repo, and a source can't teach
 * the app a new way to behave after it ships.
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
     * False for every kind this build ships: [TELEGRAM] reads from the local
     * index rather than a remote catalogue, and needs no cross-source match to
     * find the track. The property stays for a future kind that answers in a
     * round trip rather than in ten seconds.
     */
    val worthPrefetching: Boolean = false,
    /**
     * Where this kind sits in the walk, low first.
     *
     * Separate from [ordinal] so a kind keeps the position the source order
     * was written around, whatever else is added beside it.
     */
    val rank: Int,
) {
    /**
     * The listener's own Telegram library: chats, channels and groups scanned
     * for audio messages, indexed in Room and streamed through TDLib.
     *
     * The reason this fork exists: it is asked first, ahead of any catalogue,
     * so a Telegram row plays as the listener's own copy rather than as
     * somebody else's guess at the same recording. No server to configure:
     * credentials are baked in and authorization happens on the account
     * screens. `health()` reports Rejected until the listener has logged in,
     * so an unauthorized install never stalls a source walk.
     */
    TELEGRAM(
        label = "Telegram",
        detail = "کانال‌ها و گروه‌های تلگرام شما — اسکن‌شده و آفلاین قابل پخش.",
        labels = listOf("تلگرام", "آفلاین"),
        needsServer = false,
        canServeLossless = false,
        rank = 1,
    )
}
