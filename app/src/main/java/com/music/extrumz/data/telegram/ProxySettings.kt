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

        // Hex, in any of the shapes that are actually issued:
        //
        //   32   plain 16-byte
        //   34   "dd" + 16-byte (the padded/"secure" form)
        //   36+  FakeTLS -- "ee" + 16 random bytes + the host name, as hex.
        //        This is what most Iranian channels hand out, because it is the
        //        shape that gets past DPI, and the old length check (32 or 34
        //        exactly) rejected every one of them.
        //
        // Only even-length lowercase hex gets here, so a base64 secret can
        // never be mistaken for one: base64 of 16 bytes is 22 characters.
        val isHex = raw.length >= 32 && raw.length % 2 == 0 &&
            raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (isHex) {
            val lower = raw.lowercase()
            if (lower.length == 32 || lower.length == 34 || lower.startsWith("ee")) {
                return lower
            }
        }

        // Base64url, which is what a link carries when it uses - and _ where
        // base64 uses + and /. The plain decoder rejects those characters
        // outright, so the substitution is what makes these readable at all.
        val normalised = raw.replace('-', '+').replace('_', '/')
        val decoded = runCatching { java.util.Base64.getDecoder().decode(pad(normalised)) }.getOrNull()
            ?: return ""

        // 16 bytes plain, 17 with the dd flag, or the FakeTLS form: more than
        // 17, which must begin with the 0xee marker. The size test is kept
        // because it is what turns a typo into a refusal instead of a silent
        // hang -- see the doc comment above.
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
