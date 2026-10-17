package com.music.extrumz

import com.extrumz.app.data.telegram.ProxyKind
import com.extrumz.app.data.telegram.ProxyLink
import com.extrumz.app.data.telegram.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link parser is the one piece of the proxy feature with logic worth
 * testing: everything else stores what it is given.
 *
 * The inputs are the two shapes Iranian channels actually post, because the
 * whole point of accepting a pasted link is that retyping a hex secret is how
 * a working proxy gets entered wrong.
 */
class ProxyLinkTest {

    @Test
    fun `parses an MTProto link into host port and secret`() {
        val parsed = ProxyLink.parse(
            "https://t.me/proxy?server=1.2.3.4&port=443&secret=dd0f8a7b6c5d4e3f"
        )
        assertNotNull(parsed)
        assertEquals(ProxyKind.MTProto, parsed!!.kind)
        assertEquals("1.2.3.4", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("dd0f8a7b6c5d4e3f", parsed.secret)
        assertTrue(parsed.enabled)
        assertTrue(parsed.isUsable)
    }

    @Test
    fun `parses a SOCKS link with credentials`() {
        val parsed = ProxyLink.parse(
            "https://t.me/socks?server=5.6.7.8&port=1080&user=ali&pass=secret"
        )
        assertNotNull(parsed)
        assertEquals(ProxyKind.Socks5, parsed!!.kind)
        assertEquals("ali", parsed.username)
        assertEquals("secret", parsed.password)
        assertTrue(parsed.isUsable)
    }

    @Test
    fun `accepts a link copied without its scheme`() {
        // What a clipboard gives you half the time.
        val parsed = ProxyLink.parse("t.me/proxy?server=9.9.9.9&port=443&secret=abc123")
        assertNotNull(parsed)
        assertEquals("9.9.9.9", parsed!!.host)
    }

    @Test
    fun `an MTProto proxy without a secret is not usable`() {
        val parsed = ProxyLink.parse("https://t.me/proxy?server=1.2.3.4&port=443")
        // Rejected at parse: a proxy missing its secret cannot connect, and
        // saving it would look configured while never working.
        assertNull(parsed)
    }

    @Test
    fun `rejects a link that is not a proxy link`() {
        assertNull(ProxyLink.parse("https://t.me/somechannel"))
        assertNull(ProxyLink.parse("https://example.com/proxy?server=1.2.3.4&port=443"))
        assertNull(ProxyLink.parse("hello world"))
        assertNull(ProxyLink.parse(""))
    }

    @Test
    fun `rejects a port outside the valid range`() {
        assertNull(ProxyLink.parse("https://t.me/proxy?server=1.2.3.4&port=99999&secret=x"))
        assertNull(ProxyLink.parse("https://t.me/proxy?server=1.2.3.4&port=0&secret=x"))
    }

    @Test
    fun `the label never leaks the secret or the password`() {
        // Shown in settings and beside the badge, so it has to be safe to look at.
        val mtproto = ProxySettings(
            enabled = true,
            kind = ProxyKind.MTProto,
            host = "1.2.3.4",
            port = 443,
            secret = "dd0f8a7b6c5d4e3f",
        )
        assertTrue(mtproto.label.contains("1.2.3.4"))
        assertFalse(mtproto.label.contains("dd0f8a"))

        val socks = ProxySettings(
            enabled = true,
            kind = ProxyKind.Socks5,
            host = "5.6.7.8",
            port = 1080,
            username = "ali",
            password = "hunter2",
        )
        assertFalse(socks.label.contains("hunter2"))
    }
}
