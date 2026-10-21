package com.extrumz.app

import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.telegramCoordsOf
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.telegram.TelegramConfig
import com.extrumz.app.data.telegram.TelegramCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelegramCredentialsTest {

    private var initialApiId: String = ""
    private var initialApiHash: String = ""

    @Before
    fun setUp() {
        initialApiId = AppSettings.customApiId.value
        initialApiHash = AppSettings.customApiHash.value
        AppSettings.clearCustomCredentials()
    }

    @After
    fun tearDown() {
        AppSettings.customApiId.value = initialApiId
        AppSettings.customApiHash.value = initialApiHash
    }

    @Test
    fun credentials_returnsBuildConfigDefaultsWhenCustomNotSet() {
        AppSettings.clearCustomCredentials()
        val creds = TelegramConfig.credentials()
        assertEquals(BuildConfig.TG_API_ID, creds.apiId)
        assertEquals(BuildConfig.TG_API_HASH, creds.apiHash)
        assertFalse(TelegramConfig.isUsingCustomCredentials())
    }

    @Test
    fun credentials_prioritizesUserCustomCredentials() {
        val customId = 9876543
        val customHash = "0123456789abcdef0123456789abcdef"
        AppSettings.setCustomCredentials(customId.toString(), customHash)

        val creds = TelegramConfig.credentials()
        assertEquals(customId, creds.apiId)
        assertEquals(customHash, creds.apiHash)
        assertTrue(TelegramConfig.isUsingCustomCredentials())
    }

    @Test
    fun credentials_revertsToDefaultsWhenCleared() {
        AppSettings.setCustomCredentials("12345", "testhash")
        assertTrue(TelegramConfig.isUsingCustomCredentials())

        AppSettings.clearCustomCredentials()
        val creds = TelegramConfig.credentials()
        assertEquals(BuildConfig.TG_API_ID, creds.apiId)
        assertEquals(BuildConfig.TG_API_HASH, creds.apiHash)
        assertFalse(TelegramConfig.isUsingCustomCredentials())
    }

    @Test
    fun credentials_fallsBackToDefaultsOnInvalidId() {
        AppSettings.setCustomCredentials("not-a-number", "validhash")
        val creds = TelegramConfig.credentials()
        assertEquals(BuildConfig.TG_API_ID, creds.apiId)
        assertEquals(BuildConfig.TG_API_HASH, creds.apiHash)
        assertFalse(TelegramConfig.isUsingCustomCredentials())

        AppSettings.setCustomCredentials("0", "validhash")
        val credsZero = TelegramConfig.credentials()
        assertEquals(BuildConfig.TG_API_ID, credsZero.apiId)
        assertFalse(TelegramConfig.isUsingCustomCredentials())

        AppSettings.setCustomCredentials("-100", "validhash")
        val credsNeg = TelegramConfig.credentials()
        assertEquals(BuildConfig.TG_API_ID, credsNeg.apiId)
        assertFalse(TelegramConfig.isUsingCustomCredentials())
    }

    @Test
    fun credentials_fallsBackToDefaultsOnBlankHash() {
        AppSettings.setCustomCredentials("12345", "   ")
        val creds = TelegramConfig.credentials()
        assertEquals(BuildConfig.TG_API_ID, creds.apiId)
        assertEquals(BuildConfig.TG_API_HASH, creds.apiHash)
        assertFalse(TelegramConfig.isUsingCustomCredentials())
    }

    @Test
    fun credentials_toStringDoesNotExposeHashOrId() {
        val creds = TelegramCredentials(123456, "secret_hash_value_123")
        val rendered = creds.toString()
        assertFalse(rendered.contains("secret_hash_value_123"))
        assertFalse(rendered.contains("123456"))
        assertEquals("TelegramCredentials(apiId=***, apiHash=***)", rendered)
    }

    @Test
    fun telegramCoordsOf_parsesTelegramTrackIdsCorrectly() {
        val coords = telegramCoordsOf("src:tg::tg_-1001234567890_42")
        assertNotNull(coords)
        assertEquals(-1001234567890L, coords?.first)
        assertEquals(42L, coords?.second)

        val bareCoords = telegramCoordsOf("tg_123456_789")
        assertNotNull(bareCoords)
        assertEquals(123456L, bareCoords?.first)
        assertEquals(789L, bareCoords?.second)

        val nonTelegram = telegramCoordsOf("dQw4w9WgXcQ")
        assertEquals(null, nonTelegram)
    }
}
