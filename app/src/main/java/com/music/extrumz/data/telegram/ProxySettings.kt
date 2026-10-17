package com.extrumz.app.data.telegram

import android.content.Context
import android.content.SharedPreferences
import com.extrumz.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which wire protocol the proxy speaks.
 *
 * [MTProto] is first because it is the one that matters here. A SOCKS5 tunnel
 * hands TDLib a bare TCP socket per datacenter, and Telegram then sees one
 * client address opening connections to DC 4 — the file storage datacenter —
 * and answers with FLOOD_WAIT: the capture on this account showed 443 of 466
 * flood errors on DC 4, all of them through SOCKS5, and 63% of a scan's wall
 * time spent inside fifteen calls that were only waiting. An MTProto proxy is
 * Telegram's own protocol: one tunnel, a client Telegram recognises, and no
 * flood.
 */
@Serializable
enum class ProxyKind {
    MTProto,
    Socks5,
    Http,
}

/**
 * Comparative test verdict for a proxy.
 *
 * [directSeconds] is the round-trip time in seconds direct to Telegram without a proxy,
 * or -1.0 if direct connection could not reach Telegram (e.g. filtered/blocked).
 * [proxiedSeconds] is the round-trip time in seconds through this proxy, or -1.0 if unreachable.
 */
@Serializable
data class ProxyVerdict(
    val directSeconds: Double,
    val proxiedSeconds: Double,
) {
    /** True when the proxy connects and direct is blocked or slower. */
    val worthIt: Boolean
        get() = proxiedSeconds > 0 && (directSeconds < 0 || proxiedSeconds < directSeconds)
}

/**
 * A stored proxy, keeping a stable [id] independent of host:port so edits or renames
 * preserve selection and history.
 */
@Serializable
data class SavedProxy(
    val id: String,
    val settings: ProxySettings,
    /** Last measured round-trip time in seconds, or null if never tested. */
    val lastPingSeconds: Double? = null,
    val lastPingAt: Long? = null,
    /** Last measured direct round-trip time in seconds, or null if never tested. */
    val lastDirectSeconds: Double? = null,
) {
    val verdict: ProxyVerdict?
        get() = if (lastPingSeconds != null) {
            ProxyVerdict(
                directSeconds = lastDirectSeconds ?: -1.0,
                proxiedSeconds = lastPingSeconds,
            )
        } else null
}

/**
 * A proxy the user has entered, as they entered it.
 *
 * Kept dumb on purpose — this is what gets stored and shown back, not what
 * gets validated. [isUsable] says whether there is enough of it to try.
 *
 * [secret] is MTProto's shared secret, a hex string the proxy provider issues
 * alongside the host and port. It is not a password and Telegram does not
 * treat it as one, but it is the part that must not be mistyped.
 */
@Serializable
data class ProxySettings(
    val enabled: Boolean = false,
    val kind: ProxyKind = ProxyKind.MTProto,
    val host: String = "",
    val port: Int = 0,
    val secret: String = "",
    val username: String = "",
    val password: String = "",
) {
    /** Enough to attempt a connection. An MTProto proxy without its secret is not. */
    val isUsable: Boolean
        get() = host.isNotBlank() && port in 1..65535 &&
            (kind != ProxyKind.MTProto || normalizedSecret().isNotBlank())

    /**
     * The secret in the form TDLib can actually use.
     *
     * `proxyTypeMtproto` takes the secret in hex — the API says so, and it
     * does not accept base64. A `t.me/proxy` link carries base64 with the
     * padding stripped, so the decode has to happen here and the result has to
     * reach TDLib as hex.
     *
     * What this must not do is reject a secret it does not recognise, and the
     * first version did exactly that. Its length test allowed 32 or 34
     * characters, which is the plain and `dd` forms, and the FakeTLS shape —
     * `ee` + 16 random bytes + the host name, hex, the one most Iranian
     * channels hand out because it is what gets past DPI — is 36 or more. A
     * base64url secret did not survive either, since the plain decoder refuses
     * `-` and `_`. Both came back empty.
     *
     * Empty is the dangerous part, not merely wrong. An empty secret means
     * `isUsable` is false, and the client answers that by calling DisableProxy
     * and carrying on: the app connects directly, which on a filtered network
     * reaches nothing, while the settings screen keeps showing the proxy as on.
     * The user has no way to tell that from the network being down, which is
     * how three different proxies in a row "did not work" with nothing
     * pointing at the reason.
     */
    fun normalizedSecret(): String {
        val raw = secret.trim()
        if (raw.isEmpty()) return ""

        val isHex = raw.length >= 32 && raw.length % 2 == 0 &&
            raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (isHex) {
            val lower = raw.lowercase()
            if (lower.length == 32 || lower.length == 34 || lower.startsWith("ee")) {
                return lower
            }
        }

        val normalised = raw.replace('-', '+').replace('_', '/')
        val decoded = runCatching { java.util.Base64.getDecoder().decode(pad(normalised)) }.getOrNull()
            ?: return ""

        val plausible = decoded.size == 16 ||
            decoded.size == 17 ||
            (decoded.size > 17 && (decoded[0].toInt() and 0xFF) == 0xEE)
        if (!plausible) return ""

        return decoded.joinToString("") { "%02x".format(it) }
    }

    /** Restores the `=` padding a `t.me` link leaves off. */
    private fun pad(value: String): String {
        val missing = (4 - value.length % 4) % 4
        return value + "=".repeat(missing)
    }

    /**
     * True when the secret is there but cannot be read.
     *
     * Distinct from a blank one, and worth its own state: unreadable means the
     * user did supply something, the app cannot use it, and — until this
     * existed — the client answered by disabling the proxy without saying so.
     * The settings screen showed an enabled proxy over a direct connection.
     */
    val secretUnreadable: Boolean
        get() = kind == ProxyKind.MTProto && secret.isNotBlank() && normalizedSecret().isBlank()

    /** One line for the settings row. Never includes the secret or the password. */
    val label: String
        get() = when {
            !isUsable -> ""
            kind == ProxyKind.MTProto -> "MTProto · $host:$port"
            else -> "${kind.name} · $host:$port"
        }

    /** Builds the Telegram proxy link for sharing/copying. */
    fun toLink(): String = when (kind) {
        ProxyKind.MTProto -> "https://t.me/proxy?server=$host&port=$port&secret=$secret"
        ProxyKind.Socks5 -> {
            val base = "https://t.me/socks?server=$host&port=$port"
            val userParam = if (username.isNotBlank()) "&user=" + runCatching { java.net.URLEncoder.encode(username, "UTF-8") }.getOrDefault(username) else ""
            val passParam = if (password.isNotBlank()) "&pass=" + runCatching { java.net.URLEncoder.encode(password, "UTF-8") }.getOrDefault(password) else ""
            "$base$userParam$passParam"
        }
        ProxyKind.Http -> "http://$host:$port"
    }
}

/**
 * Where proxies live between launches.
 *
 * Maintains a list of [SavedProxy] entries and an active proxy id.
 * The active proxy is projected onto [current] and [settings] so the running
 * client code consumes it without knowing multiple proxies are saved.
 */
object ProxyStore {

    private const val PREFS = "extrumz.proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_KIND = "kind"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_SECRET = "secret"
    private const val KEY_USER = "username"
    private const val KEY_PASS = "password"

    private const val KEY_PROXIES_JSON = "proxies_json"
    private const val KEY_ACTIVE_ID = "active_id"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_DEV_PROXY_SEEDED = "dev_proxy_seeded"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    private val _all = MutableStateFlow<List<SavedProxy>>(emptyList())
    val all: StateFlow<List<SavedProxy>> = _all.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _masterEnabled = MutableStateFlow(false)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    private val _settings = MutableStateFlow(ProxySettings())

    /** Current settings. Collected by the settings UI and by the proxy badge. */
    val settings: StateFlow<ProxySettings> = _settings.asStateFlow()

    /** The value the client reads when it is about to connect. */
    val current: ProxySettings get() = _settings.value

    /** Hook invoked when the active proxy settings change so the client can apply them. */
    var onProxyChanged: ((ProxySettings) -> Unit)? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        initWithPrefs(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    internal fun initWithPrefs(preferences: SharedPreferences) {
        prefs = preferences
        read()
    }

    fun setEnabled(enabled: Boolean) {
        _masterEnabled.value = enabled
        if (enabled && _activeId.value == null && _all.value.isNotEmpty()) {
            _activeId.value = _all.value.first().id
        }
        persist()
        updateCurrentFlow()
        onProxyChanged?.invoke(current)
    }

    fun add(settings: ProxySettings): String {
        val id = java.util.UUID.randomUUID().toString()
        val saved = SavedProxy(id = id, settings = settings.copy(enabled = true))
        val updated = _all.value + saved
        _all.value = updated
        if (_activeId.value == null || !_masterEnabled.value) {
            _activeId.value = id
            _masterEnabled.value = true
        }
        persist()
        updateCurrentFlow()
        onProxyChanged?.invoke(current)
        return id
    }

    fun update(id: String, settings: ProxySettings) {
        val updated = _all.value.map {
            if (it.id == id) it.copy(settings = settings.copy(enabled = true)) else it
        }
        _all.value = updated
        persist()
        updateCurrentFlow()
        if (_activeId.value == id) {
            onProxyChanged?.invoke(current)
        }
    }

    fun remove(id: String) {
        val updated = _all.value.filter { it.id != id }
        _all.value = updated
        val activeRemoved = _activeId.value == id
        if (activeRemoved) {
            _activeId.value = updated.firstOrNull()?.id
            if (_activeId.value == null) {
                _masterEnabled.value = false
            }
        }
        persist()
        updateCurrentFlow()
        if (activeRemoved) {
            onProxyChanged?.invoke(current)
        }
    }

    fun activate(id: String?) {
        _activeId.value = id
        if (id != null) {
            _masterEnabled.value = true
        }
        persist()
        updateCurrentFlow()
        onProxyChanged?.invoke(current)
    }

    fun recordPing(id: String, seconds: Double, directSeconds: Double? = null) {
        val now = System.currentTimeMillis()
        val updated = _all.value.map {
            if (it.id == id) {
                it.copy(
                    lastPingSeconds = seconds,
                    lastDirectSeconds = directSeconds ?: it.lastDirectSeconds,
                    lastPingAt = now,
                )
            } else it
        }
        _all.value = updated
        persist()
    }

    fun recordPing(id: String, verdict: ProxyVerdict) {
        recordPing(id, verdict.proxiedSeconds, verdict.directSeconds)
    }

    fun save(settings: ProxySettings) {
        val active = _activeId.value
        if (active != null && _all.value.any { it.id == active }) {
            update(active, settings)
        } else {
            add(settings)
        }
    }

    private fun persist() {
        if (!::prefs.isInitialized) return
        runCatching {
            val jsonStr = json.encodeToString(_all.value)
            prefs.edit()
                .putString(KEY_PROXIES_JSON, jsonStr)
                .putString(KEY_ACTIVE_ID, _activeId.value)
                .putBoolean(KEY_MASTER_ENABLED, _masterEnabled.value)
                .apply()
        }
    }

    private fun updateCurrentFlow() {
        val active = _activeId.value
        val found = _all.value.firstOrNull { it.id == active }
        _settings.value = if (_masterEnabled.value && found != null) {
            found.settings.copy(enabled = true)
        } else {
            ProxySettings(enabled = false)
        }
    }

    private fun read() {
        val proxiesJson = prefs.getString(KEY_PROXIES_JSON, null)
        if (proxiesJson != null) {
            val list = runCatching {
                json.decodeFromString<List<SavedProxy>>(proxiesJson)
            }.getOrDefault(emptyList())
            _all.value = list
            val savedActive = prefs.getString(KEY_ACTIVE_ID, null)
            _activeId.value = if (list.any { it.id == savedActive }) savedActive else list.firstOrNull()?.id
            _masterEnabled.value = prefs.getBoolean(KEY_MASTER_ENABLED, true)
            updateCurrentFlow()
            return
        }

        // Migration from old single-proxy SharedPreferences:
        val oldHost = prefs.getString(KEY_HOST, "").orEmpty()
        val oldPort = prefs.getInt(KEY_PORT, 0)
        if (oldHost.isNotBlank() && oldPort in 1..65535) {
            val oldKind = runCatching { ProxyKind.valueOf(prefs.getString(KEY_KIND, null) ?: "") }
                .getOrDefault(ProxyKind.MTProto)
            val oldSecret = prefs.getString(KEY_SECRET, "").orEmpty()
            val oldUser = prefs.getString(KEY_USER, "").orEmpty()
            val oldPass = prefs.getString(KEY_PASS, "").orEmpty()
            val oldEnabled = prefs.getBoolean(KEY_ENABLED, false)

            val migrated = SavedProxy(
                id = java.util.UUID.randomUUID().toString(),
                settings = ProxySettings(
                    enabled = oldEnabled,
                    kind = oldKind,
                    host = oldHost,
                    port = oldPort,
                    secret = oldSecret,
                    username = oldUser,
                    password = oldPass,
                ),
            )
            val list = listOf(migrated)
            _all.value = list
            _activeId.value = migrated.id
            _masterEnabled.value = oldEnabled
            persist()
            prefs.edit().putBoolean(KEY_DEV_PROXY_SEEDED, true).apply()
            updateCurrentFlow()
            return
        }

        // Nothing saved yet: seed dev proxy if configured and not previously seeded
        val devHost = BuildConfig.PROXY_HOST
        val devPort = BuildConfig.PROXY_PORT
        val seeded = prefs.getBoolean(KEY_DEV_PROXY_SEEDED, false)
        if (!seeded && devHost.isNotBlank() && devPort in 1..65535) {
            val devProxy = SavedProxy(
                id = java.util.UUID.randomUUID().toString(),
                settings = ProxySettings(
                    enabled = true,
                    kind = ProxyKind.Socks5,
                    host = devHost,
                    port = devPort,
                ),
            )
            val list = listOf(devProxy)
            _all.value = list
            _activeId.value = devProxy.id
            _masterEnabled.value = true
            persist()
            prefs.edit().putBoolean(KEY_DEV_PROXY_SEEDED, true).apply()
            updateCurrentFlow()
            return
        }

        _all.value = emptyList()
        _activeId.value = null
        _masterEnabled.value = false
        updateCurrentFlow()
    }
}

/**
 * Reads the proxy links Telegram itself hands out.
 *
 * Every Iranian channel that shares proxies shares one of these two URLs, and
 * retyping a hex secret by hand is how a working proxy gets entered wrong:
 *
 *   https://t.me/proxy?server=1.2.3.4&port=443&secret=dd0f8a…
 *   https://t.me/socks?server=1.2.3.4&port=1080&user=u&pass=p
 *
 * Both are accepted, with or without the scheme, because what people copy is
 * whatever the channel posted. Returns null for anything else, so a mistyped
 * paste is rejected rather than saved as a broken proxy.
 *
 * Parsed by hand rather than with android.net.Uri: Uri is a stub in JVM unit
 * tests, so a parser written on it can only be exercised on a device, and this
 * is the one piece of the feature with logic worth exercising. Nothing here
 * needs Android.
 */
object ProxyLink {

    private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")

    fun parse(raw: String): ProxySettings? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // Drop any scheme — "https://", "tg://", or none at all.
        val withoutScheme = text.substringAfter("://", text)
        val host = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
        if (host !in TELEGRAM_HOSTS) return null

        val path = withoutScheme
            .substringAfter('/', "")
            .substringBefore('?')
            .trim('/')
            .lowercase()

        val params = withoutScheme
            .substringAfter('?', "")
            .split('&')
            .mapNotNull { pair ->
                val at = pair.indexOf('=')
                if (at <= 0) null else decode(pair.substring(0, at)) to decode(pair.substring(at + 1))
            }
            .toMap()

        val server = params["server"]?.trim().orEmpty()
        val port = params["port"]?.trim()?.toIntOrNull() ?: return null
        if (server.isBlank() || port !in 1..65535) return null

        return when (path) {
            "proxy" -> {
                val secret = params["secret"]?.trim().orEmpty()
                // Rejected here, not saved: an MTProto proxy without its secret
                // cannot connect, and storing it would look configured while
                // never working.
                if (secret.isBlank()) return null
                ProxySettings(
                    enabled = true,
                    kind = ProxyKind.MTProto,
                    host = server,
                    port = port,
                    secret = secret,
                )
            }
            "socks" -> ProxySettings(
                enabled = true,
                kind = ProxyKind.Socks5,
                host = server,
                port = port,
                username = params["user"]?.trim().orEmpty(),
                password = params["pass"]?.trim().orEmpty(),
            )
            else -> null
        }
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
