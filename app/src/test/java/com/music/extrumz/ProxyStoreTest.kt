package com.music.extrumz

import android.content.SharedPreferences
import com.extrumz.app.data.telegram.ProxyKind
import com.extrumz.app.data.telegram.ProxyLink
import com.extrumz.app.data.telegram.ProxySettings
import com.extrumz.app.data.telegram.ProxyStore
import com.extrumz.app.data.telegram.ProxyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStoreTest {

    private class FakeSharedPreferences(initialValues: Map<String, Any?> = emptyMap()) : SharedPreferences {
        val data = initialValues.toMutableMap()

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class Editor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values?.toSet()
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removed.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) parent.data.clear()
                removed.forEach { parent.data.remove(it) }
                parent.data.putAll(pending)
            }
        }
    }

    private fun cleanPrefs(): FakeSharedPreferences =
        FakeSharedPreferences(mapOf("dev_proxy_seeded" to true))

    @Test
    fun `two proxies added - both remain in list`() {
        val prefs = cleanPrefs()
        ProxyStore.initWithPrefs(prefs)
        val id1 = ProxyStore.add(ProxySettings(host = "proxy1.example.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))
        val id2 = ProxyStore.add(ProxySettings(host = "proxy2.example.com", port = 8443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))

        assertEquals(2, ProxyStore.all.value.size)
        assertEquals("proxy1.example.com", ProxyStore.all.value[0].settings.host)
        assertEquals("proxy2.example.com", ProxyStore.all.value[1].settings.host)
        assertEquals(id1, ProxyStore.activeId.value)
    }

    @Test
    fun `switch between proxies updates active and current`() {
        val prefs = cleanPrefs()
        ProxyStore.initWithPrefs(prefs)
        var changedProxy: ProxySettings? = null
        ProxyStore.onProxyChanged = { changedProxy = it }

        val id1 = ProxyStore.add(ProxySettings(host = "p1.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))
        val id2 = ProxyStore.add(ProxySettings(host = "p2.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))

        ProxyStore.activate(id2)
        assertEquals(id2, ProxyStore.activeId.value)
        assertEquals("p2.com", ProxyStore.current.host)
        assertEquals("p2.com", changedProxy?.host)

        ProxyStore.activate(id1)
        assertEquals(id1, ProxyStore.activeId.value)
        assertEquals("p1.com", ProxyStore.current.host)
        assertEquals("p1.com", changedProxy?.host)
    }

    @Test
    fun `remove active proxy switches active to another proxy`() {
        val prefs = cleanPrefs()
        ProxyStore.initWithPrefs(prefs)
        val id1 = ProxyStore.add(ProxySettings(host = "p1.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))
        val id2 = ProxyStore.add(ProxySettings(host = "p2.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))

        ProxyStore.activate(id1)
        ProxyStore.remove(id1)
        assertEquals(1, ProxyStore.all.value.size)
        assertEquals(id2, ProxyStore.activeId.value)
        assertEquals("p2.com", ProxyStore.current.host)
        assertTrue(ProxyStore.masterEnabled.value)

        ProxyStore.remove(id2)
        assertTrue(ProxyStore.all.value.isEmpty())
        assertNull(ProxyStore.activeId.value)
        assertFalse(ProxyStore.masterEnabled.value)
        assertFalse(ProxyStore.current.enabled)
    }

    @Test
    fun `master toggle disables and enables proxy`() {
        val prefs = cleanPrefs()
        ProxyStore.initWithPrefs(prefs)
        val id = ProxyStore.add(ProxySettings(host = "p1.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))
        assertTrue(ProxyStore.current.enabled)

        ProxyStore.setEnabled(false)
        assertFalse(ProxyStore.masterEnabled.value)
        assertFalse(ProxyStore.current.enabled)

        ProxyStore.setEnabled(true)
        assertTrue(ProxyStore.masterEnabled.value)
        assertTrue(ProxyStore.current.enabled)
    }

    @Test
    fun `persistence saves list and activeId across reloads`() {
        val prefs = cleanPrefs()
        ProxyStore.initWithPrefs(prefs)
        ProxyStore.add(ProxySettings(host = "p1.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))
        val id2 = ProxyStore.add(ProxySettings(host = "p2.com", port = 8443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))
        ProxyStore.activate(id2)

        // Simulate app relaunch
        ProxyStore.initWithPrefs(prefs)
        assertEquals(2, ProxyStore.all.value.size)
        assertEquals(id2, ProxyStore.activeId.value)
        assertEquals("p2.com", ProxyStore.current.host)
        assertTrue(ProxyStore.masterEnabled.value)
    }

    @Test
    fun `build config dev proxy seeded on first run and stays deleted when removed`() {
        val prefs = FakeSharedPreferences()
        ProxyStore.initWithPrefs(prefs)
        assertEquals(1, ProxyStore.all.value.size)
        val dev = ProxyStore.all.value[0]
        assertEquals("127.0.0.1", dev.settings.host)
        assertEquals(10808, dev.settings.port)
        assertEquals(ProxyKind.Socks5, dev.settings.kind)

        // User deletes it
        ProxyStore.remove(dev.id)
        assertTrue(ProxyStore.all.value.isEmpty())

        // Next app restart: dev proxy must NOT be re-seeded
        ProxyStore.initWithPrefs(prefs)
        assertTrue(ProxyStore.all.value.isEmpty())
    }

    @Test
    fun `migration from old single-proxy preferences preserves proxy and keeps old keys intact`() {
        val oldPrefs = FakeSharedPreferences(
            mapOf(
                "host" to "nasho.poolaki.co.uk",
                "port" to 8443,
                "kind" to "MTProto",
                "secret" to "EERighJJvXrFGRMCIMJdCQ",
                "enabled" to true,
                "username" to "",
                "password" to "",
            )
        )
        ProxyStore.initWithPrefs(oldPrefs)

        assertEquals(1, ProxyStore.all.value.size)
        val saved = ProxyStore.all.value[0]
        assertEquals("nasho.poolaki.co.uk", saved.settings.host)
        assertEquals(8443, saved.settings.port)
        assertEquals("EERighJJvXrFGRMCIMJdCQ", saved.settings.secret)
        assertEquals(ProxyKind.MTProto, saved.settings.kind)
        assertTrue(ProxyStore.masterEnabled.value)
        assertEquals(saved.id, ProxyStore.activeId.value)
        assertEquals("nasho.poolaki.co.uk", ProxyStore.current.host)
        assertTrue(ProxyStore.current.enabled)

        // Verify old keys were NOT wiped
        assertEquals("nasho.poolaki.co.uk", oldPrefs.getString("host", null))
        assertEquals(8443, oldPrefs.getInt("port", 0))
        assertEquals("EERighJJvXrFGRMCIMJdCQ", oldPrefs.getString("secret", null))
    }

    @Test
    fun `recordPing updates lastPing and verdict calculates worthIt correctly`() {
        val prefs = cleanPrefs()
        ProxyStore.initWithPrefs(prefs)
        val id = ProxyStore.add(ProxySettings(host = "p1.com", port = 443, kind = ProxyKind.MTProto, secret = "104462821249bd7ac519130220c8dd09"))

        // Direct blocked (-1.0), proxied 2.4s -> worth it!
        val verdict1 = ProxyVerdict(directSeconds = -1.0, proxiedSeconds = 2.4)
        assertTrue(verdict1.worthIt)
        ProxyStore.recordPing(id, verdict1)
        val p1 = ProxyStore.all.value.first { it.id == id }
        assertEquals(2.4, p1.lastPingSeconds!!, 0.001)
        assertEquals(-1.0, p1.lastDirectSeconds!!, 0.001)
        assertEquals(verdict1, p1.verdict)

        // Direct faster (0.5s) than proxy (2.4s) -> not worth it
        val verdict2 = ProxyVerdict(directSeconds = 0.5, proxiedSeconds = 2.4)
        assertFalse(verdict2.worthIt)
    }

    @Test
    fun `toLink generates valid telegram link that ProxyLink parses back`() {
        val mtproto = ProxySettings(
            enabled = true,
            kind = ProxyKind.MTProto,
            host = "nasho.poolaki.co.uk",
            port = 8443,
            secret = "EERighJJvXrFGRMCIMJdCQ",
        )
        val link = mtproto.toLink()
        val parsed = ProxyLink.parse(link)
        assertNotNull(parsed)
        assertEquals(mtproto.host, parsed!!.host)
        assertEquals(mtproto.port, parsed.port)
        assertEquals(mtproto.kind, parsed.kind)
        assertEquals(mtproto.secret, parsed.secret)
    }
}
