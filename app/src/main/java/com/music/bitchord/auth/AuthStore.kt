package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Encrypted-at-rest storage for the Google account sessions.
 *
 * A session is the credential jar a Google login arrived with, kept here rather
 * than in the plain settings because it grants full access to that account. It
 * is the state [isSignedIn] answers from.
 *
 * A restored file whose keyset this device can't unwrap is recreated, and a
 * Keystore that can't be used at all degrades to plain prefs rather than
 * crashing on launch; see [EncryptedPrefs]. Kept out of backups for the first
 * reason, in backup_rules.xml and data_extraction_rules.xml.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences =
        EncryptedPrefs.open(context, "bitchord_auth", "bitchord_auth_plain")

    /**
     * The durable account registry, keyed by [KEY_SESSIONS].
     */
    var sessions: List<GoogleAccountSession>
        get() = sessionsFromJson(prefs.getString(KEY_SESSIONS, null))
        set(value) = replaceSessions(value)

    var activeAccountId: String?
        get() = prefs.getString(KEY_ACTIVE_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()

    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        prefs.edit().putString(KEY_SESSIONS, value.toJson()).apply()
    }

    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val next = sessions.filterNot { it.accountId == session.accountId } + session
        replaceSessions(next)
        if (activate) select(session.accountId)
    }

    fun select(accountId: String) {
        activeAccountId = accountId
    }

    fun removeAccount(accountId: String): GoogleAccountSession? {
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty())
        return fallback
    }

    /** Whether any account session is stored on this device. */
    val isSignedIn: Boolean
        get() = activeSession != null

    /**
     * The channel the listener chose to act as, if they chose one.
     *
     * Stored beside the session rather than in the plain settings because it is
     * only meaningful with that session and must not outlive it: a `dataSyncId`
     * from one login, replayed under another, is answered with 401 on every
     * request. [signOut] clears it for that reason.
     *
     * A null [channelPageId] with a [channelDataSyncId] set is a real state,
     * not an absent one — it is the account's own channel, deliberately chosen
     * over a brand channel the web player would otherwise default to.
     */
    val channelPageId: String? get() = prefs.getString(KEY_CHANNEL_PAGE_ID, null)
    val channelDataSyncId: String? get() = prefs.getString(KEY_CHANNEL_DATASYNC_ID, null)

    /** The chosen channel's display name, for the settings row. */
    val channelName: String? get() = prefs.getString(KEY_CHANNEL_NAME, null)

    /** Which Google account in the jar it belongs to; null means "as the shell says". */
    val channelAuthUser: String? get() = prefs.getString(KEY_CHANNEL_AUTH_USER, null)

    fun selectChannel(
        pageId: String?,
        dataSyncId: String?,
        name: String?,
        authUser: String? = null,
    ) = prefs.edit()
        .putString(KEY_CHANNEL_PAGE_ID, pageId)
        .putString(KEY_CHANNEL_DATASYNC_ID, dataSyncId)
        .putString(KEY_CHANNEL_NAME, name)
        .putString(KEY_CHANNEL_AUTH_USER, authUser)
        .apply()

    /**
     * The chosen channel's name, once something knows it.
     *
     * Separate from [selectChannel] because the two are learned at different
     * times: a channel picked in the in-app browser is identified by ids the
     * moment it is picked, and named only after the next account fetch comes
     * back to say what it is called.
     */
    fun setChannelName(name: String?) =
        prefs.edit().putString(KEY_CHANNEL_NAME, name).apply()

    /** Back to whichever channel YouTube Music serves by default. */
    fun clearChannel() = prefs.edit()
        .remove(KEY_CHANNEL_PAGE_ID)
        .remove(KEY_CHANNEL_DATASYNC_ID)
        .remove(KEY_CHANNEL_NAME)
        .remove(KEY_CHANNEL_AUTH_USER)
        .apply()

    /**
     * Signs out of the stored account only — any other in-app login is a
     * separate account.
     */
    fun signOut() {
        prefs.edit().remove(KEY_SESSIONS)
            .remove(KEY_ACTIVE_ACCOUNT).apply()
        clearChannel()
    }

    companion object {
        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_CHANNEL_PAGE_ID = "channel_page_id"
        private const val KEY_CHANNEL_DATASYNC_ID = "channel_datasync_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_CHANNEL_AUTH_USER = "channel_auth_user"
    }
}
