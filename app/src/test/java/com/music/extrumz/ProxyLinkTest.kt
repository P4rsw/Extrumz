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
            "https://t.me/proxy?server=1.2.3.4&port=443&secret=dd0f8a7b6c5d4e3f00112233445566aa"
        )
        assertNotNull(parsed)
        assertEquals(ProxyKind.MTProto, parsed!!.kind)
        assertEquals("1.2.3.4", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("dd0f8a7b6c5d4e3f00112233445566aa", parsed.secret)
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
    fun `an unpadded base64 secret becomes hex`() {
        // The shape a t.me link actually carries: 22 chars, no "=". TDLib
        // cannot tell this from a hex string and derives the wrong key, so the
        // proxy never answers. Decoding it here removes the guess.
        val settings = ProxySettings(
            enabled = true,
            kind = ProxyKind.MTProto,
            host = "nasho.poolaki.co.uk",
            port = 8443,
            secret = "EERighJJvXrFGRMCIMJdCQ",
        )
        val hex = settings.normalizedSecret()
        assertEquals(32, hex.length)
        assertTrue(hex.all { it.isDigit() || it in 'a'..'f' })
        // Checked against an independent decoder: base64 of this secret is
        // "Incorrect padding" without the "==" the link leaves off, and decodes
        // to these sixteen bytes with it. That mismatch is the whole bug.
        assertEquals("104462821249bd7ac519130220c25d09", hex)
    }

    @Test
    fun `a padded base64 secret decodes to the same key`() {
        val unpadded = ProxySettings(
            kind = ProxyKind.MTProto, secret = "EERighJJvXrFGRMCIMJdCQ",
        ).normalizedSecret()
        val padded = ProxySettings(
            kind = ProxyKind.MTProto, secret = "EERighJJvXrFGRMCIMJdCQ==",
        ).normalizedSecret()
        assertEquals(unpadded, padded)
    }

    @Test
    fun `a hex secret is passed through unchanged`() {
        // Already in the form TDLib wants — re-encoding it would break it.
        val hex = "dd0f8a7b6c5d4e3f0a1b2c3d4e5f6071"
        assertEquals(hex, ProxySettings(kind = ProxyKind.MTProto, secret = hex).normalizedSecret())
    }

    @Test
    fun `a proxy with an undecodable secret is not usable`() {
        // Better refused than connected-and-silent, which is the failure this
        // whole path exists to avoid.
        val broken = ProxySettings(
            enabled = true, kind = ProxyKind.MTProto, host = "1.2.3.4", port = 443, secret = "!!!",
        )
        assertFalse(broken.isUsable)
    }

    @Test
    fun `the label never leaks the secret or the password`() {
        // Shown in settings and beside the badge, so it has to be safe to look at.
        val mtproto = ProxySettings(
            enabled = true,
            kind = ProxyKind.MTProto,
            host = "1.2.3.4",
            port = 443,
            secret = "dd0f8a7b6c5d4e3f00112233445566aa",
        )
        assertTrue(mtproto.isUsable)
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
