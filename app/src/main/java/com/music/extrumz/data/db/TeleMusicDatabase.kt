package com.extrumz.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.extrumz.app.data.db.dao.ChatDao
import com.extrumz.app.data.db.dao.LikedDao
import com.extrumz.app.data.db.dao.PlayHistoryDao
import com.extrumz.app.data.db.dao.PlaylistDao
import com.extrumz.app.data.db.dao.PendingOperationDao
import com.extrumz.app.data.db.dao.TrackDao
import com.extrumz.app.data.db.entity.ChatSource
import com.extrumz.app.data.db.entity.PendingOperation
import com.extrumz.app.data.db.entity.LikedVideo
import com.extrumz.app.data.db.entity.PlayHistory
import com.extrumz.app.data.db.entity.Playlist
import com.extrumz.app.data.db.entity.PlaylistTrack
import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.db.entity.TrackSource

@Database(
    entities = [
        Track::class,
        TrackSource::class,
        ChatSource::class,
        Playlist::class,
        PlaylistTrack::class,
        PlayHistory::class,
        PendingOperation::class,
        LikedVideo::class
    ],
    version = 6,
    exportSchema = false
)
abstract class ExtrumzDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun chatDao(): ChatDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun likedDao(): LikedDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        // Same file name and schema version as the standalone Extrumz app,
        // so an upgrade that already has a scanned library keeps it verbatim.
        const val NAME = "extrumz.db"
    }
}
