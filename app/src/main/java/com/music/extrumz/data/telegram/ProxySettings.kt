package com.extrumz.app.data.telegram

import android.content.Context
import com.extrumz.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
enum class ProxyKind {
    MTProto,
    Socks5,
    Http,
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
            (kind != ProxyKind.MTProto || secret.isNotBlank())

    /** One line for the settings row. Never includes the secret or the password. */
    val label: String
        get() = when {
            !isUsable -> ""
            kind == ProxyKind.MTProto -> "MTProto · $host:$port"
            else -> "${kind.name} · $host:$port"
        }
}

/**
 * Where the proxy lives between launches.
 *
 * It used to be BuildConfig.PROXY_HOST/PROXY_PORT, read from local.properties
 * at build time, which made it a developer's setting: a user who cannot reach
 * Telegram without a proxy — everyone this app is for — had no way to supply
 * one at all. This is the user-facing home for it.
 *
 * BuildConfig is still consulted as a fallback so an existing dev setup keeps
 * working untouched; anything saved here wins.
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

    private lateinit var prefs: android.content.SharedPreferences

    private val _settings = MutableStateFlow(ProxySettings())

    /** Current settings. Collected by the settings UI and by the proxy badge. */
    val settings: StateFlow<ProxySettings> = _settings.asStateFlow()

    /** The value the client reads when it is about to connect. */
    val current: ProxySettings get() = _settings.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = read()
    }

    fun save(settings: ProxySettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_KIND, settings.kind.name)
            .putString(KEY_HOST, settings.host)
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_SECRET, settings.secret)
            .putString(KEY_USER, settings.username)
            .putString(KEY_PASS, settings.password)
            .apply()
        _settings.value = settings
    }

    private fun read(): ProxySettings {
        val stored = ProxySettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            kind = runCatching { ProxyKind.valueOf(prefs.getString(KEY_KIND, null) ?: "") }
                .getOrDefault(ProxyKind.MTProto),
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 0),
            secret = prefs.getString(KEY_SECRET, "").orEmpty(),
            username = prefs.getString(KEY_USER, "").orEmpty(),
            password = prefs.getString(KEY_PASS, "").orEmpty(),
        )
        if (stored.host.isNotBlank()) return stored

        // Nothing saved yet: an existing build-time proxy, if the developer set one.
        val devHost = BuildConfig.PROXY_HOST
        val devPort = BuildConfig.PROXY_PORT
        return if (devHost.isNotBlank() && devPort > 0) {
            ProxySettings(
                enabled = true,
                kind = ProxyKind.Socks5,
                host = devHost,
                port = devPort,
            )
        } else {
            stored
        }
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
