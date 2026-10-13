package com.extrumz.app.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.extrumz.app.auth.EncryptedPrefs
import com.extrumz.app.data.TrackLog
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.settings.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.UUID

/**
 * One configured source: which protocol, which address.
 *
 * Stored in encrypted prefs — [baseUrl] is an address the user may have typed
 * for a private install, not something to leave sitting in plain-text
 * SharedPreferences on a device someone else might get into.
 */
@Serializable
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: SourceKind,
    /** What the user called it. Blank falls back to the server's host, or the kind's own label. */
    val label: String = "",
    val baseUrl: String = "",
    /** Sources ship on; the one source this build has (Telegram) is never off by default. */
    val enabled: Boolean = true,
) {
    /** What the sources screen and the player show. Never blank. */
    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?: kind.label
        }

    /** Whether this has enough filled in to be worth contacting at all. */
    val isComplete: Boolean
        get() = !kind.needsServer || baseUrl.isNotBlank()
}

/**
 * The user's sources: exactly one, Telegram.
 *
 * Phase 2 strips every external catalogue. Only [SourceKind.TELEGRAM] is
 * seeded, stored configs of retired kinds are dropped at [init], and
 * [enabledConfigs] refuses anything else — so even a config added before the
 * editors were removed can never produce a resolve. Nothing new builds a
 * second kind.
 */
object SourceRegistry {

    private const val TAG = "Extrumz"

    private lateinit var prefs: SharedPreferences

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Every configured source, enabled or not. */
    val configs = MutableStateFlow<List<SourceConfig>>(emptyList())

    /**
     * Built instances, keyed by config id, rebuilt whenever [configs] changes.
     *
     * Held rather than constructed per call so that a source with any warmed
     * state keeps it across tracks instead of re-probing on every resolve.
     */
    private var instances: Map<String, MusicSource> = emptyMap()

    fun init(context: Context) {
        // Repair and degradation are EncryptedPrefs' own; see EncryptedPrefs.
        prefs = EncryptedPrefs.open(context, "extrumz_sources", "extrumz_sources_plain")

        val stored = prefs.getString(KEY_SOURCES, null)?.let(::decodeStored) ?: emptyList()
        val after = sourcesForInit(stored)

        // Publish state in one preferences edit; a process dying before it
        // lands only means the retired configs are filtered again next launch.
        publish(after, persist = false)
        if (after != stored) {
            prefs.edit()
                .putString(KEY_SOURCES, json.encodeToString(ListSerializer(SourceConfig.serializer()), after))
                .apply()
        }
    }

    /**
     * Built-in seeding and source retirement, kept pure for tests.
     *
     * Telegram is the only built-in: configs of any other kind left over from
     * an older install are dropped rather than kept around switched off, so
     * nothing downstream has to remember they used to exist.
     */
    internal fun sourcesForInit(stored: List<SourceConfig>): List<SourceConfig> {
        // Seeded rather than persisted-on-first-write, so a build adding a new
        // built-in kind picks it up for existing installs too.
        return stored.filter { it.kind == SourceKind.TELEGRAM } + BUILT_IN_KINDS
            .filter { kind -> stored.none { it.kind == kind } }
            .map { SourceConfig(kind = it) }
    }

    /**
     * Decodes a stored source list one entry at a time rather than as a
     * single list, so one entry naming a kind this build no longer has —
     * left over from before a kind was retired — doesn't take every other
     * entry down with it. A strict `List<SourceConfig>` decode fails whole:
     * one bad enum value and the user's real, working source config is
     * silently gone along with it.
     */
    private fun decodeStored(raw: String): List<SourceConfig> {
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { return emptyList() }
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "dropping unreadable stored source: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * The enabled sources in the order they are asked.
     *
     * The user's standing choice and nothing else. Both playback and downloads
     * start here, so a disabled source is excluded from both. A stream is
     * budgeted further by [activeForPlayback]; downloads deliberately apply no
     * connection-quality ceiling — see [SourceResolver.forDownload].
     */
    fun active(): List<MusicSource> =
        enabledConfigs(configs.value)
            .sortedBy { it.kind.rank }
            .mapNotNull { instances[it.id] }

    /** The common eligibility gate used by playback and download source walks. */
    internal fun enabledConfigs(configs: List<SourceConfig>): List<SourceConfig> =
        configs.filter { it.enabled && it.isComplete && it.kind == SourceKind.TELEGRAM }

    /**
     * [active], minus the sources the rung chosen in settings does not pay
     * for — see [AudioQuality.permits].
     *
     * Every path that starts or plans a *stream* asks this rather than
     * [active], so the single rung in settings stays the answer to "what does
     * this minute cost". Downloads deliberately keep asking [active]: what a
     * saved file is worth is about
     * how a saved file is kept, and when it may be fetched is
     * [AppSettings.wifiOnlyDownloads][com.extrumz.app.data.settings.AppSettings.wifiOnlyDownloads]'s.
     */
    fun activeForPlayback(): List<MusicSource> {
        val ceiling = AppSettings.audioQuality.value
        return active().filter { ceiling.permits(it.kind) }
    }

    fun instance(configId: String): MusicSource? = instances[configId]

    fun config(configId: String): SourceConfig? = configs.value.firstOrNull { it.id == configId }

    // ── Editing ─────────────────────────────────────────────────────────

    fun add(config: SourceConfig) = publish(configs.value + config)

    fun update(config: SourceConfig) =
        publish(configs.value.map { if (it.id == config.id) config else it })

    fun remove(configId: String) {
        val target = config(configId) ?: return
        if (target.kind in BUILT_IN_KINDS) return
        publish(configs.value.filterNot { it.id == configId })
    }

    /**
     * Turns one source on or off.
     *
     * Telegram is the only source, and disabling it is not offered: the app
     * without Telegram has no catalogue left to resolve from.
     */
    fun setEnabled(configId: String, enabled: Boolean) {
        if (!enabled && config(configId)?.kind == SourceKind.TELEGRAM) return
        publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
    }

    private fun publish(next: List<SourceConfig>, persist: Boolean = true) {
        configs.value = next
        // Rebuilt against the previous map so that an untouched source keeps
        // the instance it already had, rather than being replaced by an
        // identical-but-cold one every time an unrelated row is toggled.
        val previous = instances
        instances = next.associate { config ->
            val existing = previous[config.id]?.takeIf { it.configuredBy(config) }
            config.id to (existing ?: build(config))
        }
        if (persist && ::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_SOURCES, json.encodeToString(ListSerializer(SourceConfig.serializer()), next))
                .apply()
        }
    }

    private fun build(config: SourceConfig): MusicSource = TelegramSource(config)

    /**
     * Whether an already-built instance still matches its stored config —
     * false after an edit that changes where it points, which is exactly when
     * the warm instance must be thrown away.
     */
    private fun MusicSource.configuredBy(config: SourceConfig): Boolean =
        this is ConfigBacked && this.config == config

    /** Implemented by sources that carry their [SourceConfig], so [publish] can tell a real edit from a no-op. */
    internal interface ConfigBacked {
        val config: SourceConfig
    }

    // ── Track identity ──────────────────────────────────────────────────

    /**
     * A source-backed track's id, as it travels through the queue.
     *
     * Packed into the existing [Song.videoId][com.extrumz.app.data.model.Song.videoId]
     * rather than added beside it: that field is the app's media id everywhere —
     * the queue, the notification, the history, the like state — and a second
     * identity field would have to be threaded through every one of them, with
     * each place that forgot silently falling back to treating the track as a
     * bare id.
     */
    fun trackKey(configId: String, trackId: String) = "$PREFIX$configId$SEPARATOR$trackId"

    /** The `(configId, trackId)` inside a [trackKey], or null if this is not a source-backed key. */
    fun parseTrackKey(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.removePrefix(PREFIX)
        val cut = body.indexOf(SEPARATOR)
        if (cut <= 0) return null
        return body.substring(0, cut) to body.substring(cut + SEPARATOR.length)
    }

    /** The playback URI for a source-backed track; [PlaybackService] resolves it at open time. */
    fun trackUri(configId: String, trackId: String): String =
        Uri.Builder()
            .scheme("extrumz")
            .authority("source")
            .appendQueryParameter("s", configId)
            .appendQueryParameter("t", trackId)
            .build()
            .toString()

    private val BUILT_IN_KINDS = listOf(SourceKind.TELEGRAM)

    private const val KEY_SOURCES = "sources"
    private const val PREFIX = "src:"
    private const val SEPARATOR = "::"
}
