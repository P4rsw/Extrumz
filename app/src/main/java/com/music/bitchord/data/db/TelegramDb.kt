package com.music.bitchord.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.music.bitchord.data.db.dao.ChatDao
import com.music.bitchord.data.db.dao.PlayHistoryDao
import com.music.bitchord.data.db.dao.PlaylistDao
import com.music.bitchord.data.db.dao.PendingOperationDao
import com.music.bitchord.data.db.dao.TrackDao

/**
 * Process-wide holder for the Telegram library database, following the app's
 * `object … init(context)` convention (SourceRegistry, AppSettings, …).
 *
 * The database itself opens lazily on first DAO access, so startup never pays
 * for a feature the listener may not have enabled yet. BundledSQLiteDriver is
 * required: FTS5 is unavailable in the framework's SQLite below API 30 and
 * this app's minSdk is 26.
 */
object TelegramDb {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val database: TeleMusicDatabase by lazy {
        Room.databaseBuilder<TeleMusicDatabase>(
            appContext,
            TeleMusicDatabase::class.java,
            TeleMusicDatabase.NAME
        )
            .setDriver(BundledSQLiteDriver())
            .addCallback(Fts5Callbacks)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }

    val trackDao: TrackDao get() = database.trackDao()
    val chatDao: ChatDao get() = database.chatDao()
    val playlistDao: PlaylistDao get() = database.playlistDao()
    val playHistoryDao: PlayHistoryDao get() = database.playHistoryDao()
    val pendingOperationDao: PendingOperationDao get() = database.pendingOperationDao()
}
