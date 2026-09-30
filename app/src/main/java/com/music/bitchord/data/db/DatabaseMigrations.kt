package com.music.bitchord.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * FTS5 setup for databases that already exist (onCreate only covers fresh installs).
 * Safe to run multiple times via IF NOT EXISTS for the virtual table; triggers
 * are dropped and recreated so column lists stay in sync with the tracks schema.
 */
object Fts5Migrations {

    fun ensureFts(connection: SQLiteConnection) {
        connection.execSQL("DROP TRIGGER IF EXISTS tracks_ai")
        connection.execSQL("DROP TRIGGER IF EXISTS tracks_ad")
        connection.execSQL("DROP TRIGGER IF EXISTS tracks_au")
        connection.execSQL("DROP TABLE IF EXISTS tracks_fts")
        Fts5Callbacks.createFtsAndTriggers(connection)
        rebuildAndVerify(connection)
    }

    /**
     * Rebuild external-content FTS index and verify row counts match.
     * On failure, recreates FTS from scratch via triggers + full re-index.
     */
    private fun rebuildAndVerify(connection: SQLiteConnection) {
        val rebuilt = runCatching {
            connection.execSQL("INSERT INTO tracks_fts(tracks_fts) VALUES('rebuild')")
        }.isSuccess
        if (rebuilt && countsMatch(connection)) return

        // Fallback: recreate FTS table and re-populate via INSERT ... SELECT.
        runCatching {
            connection.execSQL("DROP TABLE IF EXISTS tracks_fts")
            Fts5Callbacks.createFtsAndTriggers(connection)
            connection.execSQL(
                """
                INSERT INTO tracks_fts(rowid, performer, title, album, tags, caption)
                SELECT id,
                    COALESCE(performer, ''),
                    COALESCE(title, ''),
                    COALESCE(album, ''),
                    COALESCE(tags, ''),
                    COALESCE(caption, '')
                FROM tracks
                """.trimIndent()
            )
            if (!countsMatch(connection)) {
                error("FTS5 row count mismatch after rebuild")
            }
        }.onFailure {
            // Last resort: leave empty FTS (search degrades to LIKE fallback).
            runCatching { connection.execSQL("DROP TABLE IF EXISTS tracks_fts") }
            Fts5Callbacks.createFtsAndTriggers(connection)
        }
    }

    private fun countsMatch(connection: SQLiteConnection): Boolean =
        runCatching {
            val tracks = queryLong(connection, "SELECT COUNT(*) FROM tracks")
            val fts = queryLong(connection, "SELECT COUNT(*) FROM tracks_fts")
            tracks == fts
        }.getOrDefault(false)

    private fun queryLong(connection: SQLiteConnection, sql: String): Long {
        val stmt = connection.prepare(sql)
        try {
            stmt.step()
            return stmt.getLong(0)
        } finally {
            stmt.close()
        }
    }
}

/**
 * Room migrations registry. Version 1 is initial; version 2 adds the integer
 * rowid primary key used by FTS5 content_rowid and rebuilds FTS5.
 */
object DatabaseMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE tracks_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fileUniqueId TEXT NOT NULL,
                    fileId TEXT NOT NULL,
                    performer TEXT,
                    title TEXT,
                    duration INTEGER NOT NULL,
                    album TEXT,
                    trackNumber INTEGER,
                    genre TEXT,
                    year INTEGER,
                    coverUrl TEXT,
                    lyrics TEXT,
                    dateAdded INTEGER NOT NULL,
                    sourceChatId INTEGER NOT NULL,
                    sourceMessageId INTEGER NOT NULL,
                    mediaAlbumId INTEGER,
                    isFavorite INTEGER NOT NULL DEFAULT 0,
                    playCount INTEGER NOT NULL DEFAULT 0,
                    lastPlayed INTEGER,
                    tags TEXT NOT NULL DEFAULT '',
                    fileSize INTEGER NOT NULL,
                    mimeType TEXT NOT NULL,
                    caption TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO tracks_new (
                    id, fileUniqueId, fileId, performer, title, duration, album,
                    trackNumber, genre, year, coverUrl, lyrics, dateAdded,
                    sourceChatId, sourceMessageId, mediaAlbumId, isFavorite,
                    playCount, lastPlayed, tags, fileSize, mimeType, caption
                )
                SELECT
                    rowid, fileUniqueId, fileId, performer, title, duration, album,
                    trackNumber, genre, year, coverUrl, lyrics, dateAdded,
                    sourceChatId, sourceMessageId, mediaAlbumId, isFavorite,
                    playCount, lastPlayed, tags, fileSize, mimeType, caption
                FROM tracks
                """.trimIndent()
            )
            connection.execSQL("DROP TABLE tracks")
            connection.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_tracks_fileUniqueId ON tracks(fileUniqueId)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tracks_sourceChatId_dateAdded ON tracks(sourceChatId, dateAdded)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tracks_performer_album_trackNumber ON tracks(performer, album, trackNumber)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tracks_isFavorite_dateAdded ON tracks(isFavorite, dateAdded)"
            )
            Fts5Migrations.ensureFts(connection)
        }
    }

    /**
     * Version 2 → 3: metadata columns become NOT NULL with empty defaults
     * (NULL coalesced during copy), performer/title use NOCASE collation,
     * and the full entity index set is recreated before FTS5 rebuild.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            // Drop external-content FTS before its content table is replaced.
            connection.execSQL("DROP TABLE IF EXISTS tracks_fts")
            connection.execSQL(
                """
                CREATE TABLE tracks_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fileUniqueId TEXT NOT NULL,
                    fileId TEXT NOT NULL,
                    performer TEXT NOT NULL COLLATE NOCASE,
                    title TEXT NOT NULL COLLATE NOCASE,
                    duration INTEGER NOT NULL,
                    album TEXT NOT NULL,
                    trackNumber INTEGER NOT NULL,
                    genre TEXT NOT NULL,
                    year INTEGER NOT NULL,
                    coverUrl TEXT NOT NULL,
                    lyrics TEXT NOT NULL,
                    dateAdded INTEGER NOT NULL,
                    sourceChatId INTEGER NOT NULL,
                    sourceMessageId INTEGER NOT NULL,
                    mediaAlbumId INTEGER,
                    isFavorite INTEGER NOT NULL,
                    playCount INTEGER NOT NULL,
                    lastPlayed INTEGER,
                    tags TEXT NOT NULL,
                    fileSize INTEGER NOT NULL,
                    mimeType TEXT NOT NULL,
                    caption TEXT NOT NULL
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO tracks_new (
                    id, fileUniqueId, fileId, performer, title, duration, album,
                    trackNumber, genre, year, coverUrl, lyrics, dateAdded,
                    sourceChatId, sourceMessageId, mediaAlbumId, isFavorite,
                    playCount, lastPlayed, tags, fileSize, mimeType, caption
                )
                SELECT
                    id, fileUniqueId, fileId,
                    COALESCE(performer, ''), COALESCE(title, ''), duration,
                    COALESCE(album, ''), COALESCE(trackNumber, 0), COALESCE(genre, ''),
                    COALESCE(year, 0), COALESCE(coverUrl, ''), COALESCE(lyrics, ''),
                    dateAdded, sourceChatId, sourceMessageId, mediaAlbumId,
                    isFavorite, playCount, lastPlayed,
                    COALESCE(tags, ''), fileSize, mimeType, COALESCE(caption, '')
                FROM tracks
                """.trimIndent()
            )
            connection.execSQL("DROP TABLE tracks")
            connection.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
            createAllTrackIndices(connection)
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_operations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    type TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            Fts5Migrations.ensureFts(connection)
        }
    }

    private fun createAllTrackIndices(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_tracks_fileUniqueId ON tracks(fileUniqueId)"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_fileId ON tracks(fileId)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_performer ON tracks(performer)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_title ON tracks(title)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_album ON tracks(album)")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_sourceChatId ON tracks(sourceChatId)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_mediaAlbumId ON tracks(mediaAlbumId)"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_dateAdded ON tracks(dateAdded)")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_sourceChatId_dateAdded " +
                "ON tracks(sourceChatId, dateAdded)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_performer_album_trackNumber " +
                "ON tracks(performer, album, trackNumber)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_isFavorite_dateAdded " +
                "ON tracks(isFavorite, dateAdded)"
        )
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

    /** One-shot FTS repair for pre-existing databases opened before FTS triggers existed. */
    val FTS_REPAIR = object : Migration(1, 1) {
        override fun migrate(connection: SQLiteConnection) {
            Fts5Migrations.ensureFts(connection)
        }
    }
}
