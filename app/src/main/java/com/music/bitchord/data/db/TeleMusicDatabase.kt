package com.music.bitchord.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.music.bitchord.data.db.dao.ChatDao
import com.music.bitchord.data.db.dao.PlayHistoryDao
import com.music.bitchord.data.db.dao.PlaylistDao
import com.music.bitchord.data.db.dao.PendingOperationDao
import com.music.bitchord.data.db.dao.TrackDao
import com.music.bitchord.data.db.entity.ChatSource
import com.music.bitchord.data.db.entity.PendingOperation
import com.music.bitchord.data.db.entity.PlayHistory
import com.music.bitchord.data.db.entity.Playlist
import com.music.bitchord.data.db.entity.PlaylistTrack
import com.music.bitchord.data.db.entity.Track

@Database(
    entities = [
        Track::class,
        ChatSource::class,
        Playlist::class,
        PlaylistTrack::class,
        PlayHistory::class,
        PendingOperation::class
    ],
    version = 3,
    exportSchema = false
)
abstract class TeleMusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun chatDao(): ChatDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        // Same file name and schema version as the standalone TeleMusic app,
        // so an upgrade that already has a scanned library keeps it verbatim.
        const val NAME = "telemusic.db"
    }
}
