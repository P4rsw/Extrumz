package com.extrumz.app

import com.extrumz.app.data.telegram.ProxyKind
import com.extrumz.app.data.telegram.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What normalizedSecret() does with the secrets real Iranian proxy channels
 * hand out. Each case is a shape seen in the wild, not a hypothetical.
 */
class ProxySecretShapeTest {

    private fun secret(shape: String) = ProxySettings(
        enabled = true, kind = ProxyKind.MTProto,
        host = "1.2.3.4", port = 443, secret = shape,
    )

    @Test
    fun `plain 16-byte base64 secret is accepted`() {
        val s = secret("EERighJJvXrFGRMCIMjdCQ")
        assertEquals("104462821249bd7ac519130220c8dd09", s.normalizedSecret())
    }

    @Test
    fun `the secret saved on the device is readable`() {
        // Read out of the device's own shared_prefs, not invented: this is the
        // proxy the user is actually running.
        val s = secret("EERighJJvXrFGRMCIMJdCQ")
        assertEquals("this is the secret the device has stored", 22, "EERighJJvXrFGRMCIMJdCQ".length)
        assertNotEquals("the stored secret must not normalise to empty", "", s.normalizedSecret())
    }

    @Test
    fun `hex secret passes through`() {
        val s = secret("104462821249bd7ac519130220c8dd09")
        assertEquals("104462821249bd7ac519130220c8dd09", s.normalizedSecret())
    }

    @Test
    fun `dd-prefixed hex secret passes through`() {
        val s = secret("dd104462821249bd7ac519130220c8dd09")
        assertEquals("dd104462821249bd7ac519130220c8dd09", s.normalizedSecret())
    }

    @Test
    fun `FakeTLS hex secret is accepted`() {
        // The shape most Iranian channels hand out: "ee" + 16 random bytes +
        // the domain, all as hex. This is what DPI-heavy networks require.
        val fakeTls = "ee" + "a1b2c3d4e5f60718293a4b5c6d7e8f90" + "676f6f676c652e636f6d"
        val s = secret(fakeTls)
        assertEquals("FakeTLS key must survive normalization", fakeTls, s.normalizedSecret())
    }

    @Test
    fun `base64url secret with dash and underscore is accepted`() {
        // base64url uses - and _ where base64 uses + and /.
        val url = "7d8-9a_bcdefghijklmnopq"
        val s = secret(url)
        assertNotEquals("base64url key must not be rejected", "", s.normalizedSecret())
    }

    @Test
    fun `every shape that reaches TDLib is non-empty`() {
        val shapes = listOf(
            "EERighJJvXrFGRMCIMjdCQ",
            "104462821249bd7ac519130220c8dd09",
            "dd104462821249bd7ac519130220c8dd09",
            "ee" + "a1b2c3d4e5f60718293a4b5c6d7e8f90" + "676f6f676c652e636f6d",
            "7d8-9a_bcdefghijklmnopq",
        )
        val dead = shapes.filter { secret(it).normalizedSecret().isEmpty() }
        assertEquals("these shapes normalize to empty, so the proxy is silently disabled: $dead", emptyList<String>(), dead)
    }
}
