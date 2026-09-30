package com.music.bitchord.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Fts5Callbacks : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        createFtsAndTriggers(connection)
    }

    fun createFtsAndTriggers(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(
                performer, title, album, tags, caption,
                content='tracks', content_rowid='id'
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS tracks_ai AFTER INSERT ON tracks BEGIN
                INSERT INTO tracks_fts(rowid, performer, title, album, tags, caption)
                VALUES (
                    new.id,
                    COALESCE(new.performer, ''),
                    COALESCE(new.title, ''),
                    COALESCE(new.album, ''),
                    COALESCE(new.tags, ''),
                    COALESCE(new.caption, '')
                );
            END
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS tracks_ad AFTER DELETE ON tracks BEGIN
                INSERT INTO tracks_fts(tracks_fts, rowid, performer, title, album, tags, caption)
                VALUES (
                    'delete',
                    old.id,
                    COALESCE(old.performer, ''),
                    COALESCE(old.title, ''),
                    COALESCE(old.album, ''),
                    COALESCE(old.tags, ''),
                    COALESCE(old.caption, '')
                );
            END
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS tracks_au AFTER UPDATE ON tracks BEGIN
                INSERT INTO tracks_fts(tracks_fts, rowid, performer, title, album, tags, caption)
                VALUES (
                    'delete',
                    old.id,
                    COALESCE(old.performer, ''),
                    COALESCE(old.title, ''),
                    COALESCE(old.album, ''),
                    COALESCE(old.tags, ''),
                    COALESCE(old.caption, '')
                );
                INSERT INTO tracks_fts(rowid, performer, title, album, tags, caption)
                VALUES (
                    new.id,
                    COALESCE(new.performer, ''),
                    COALESCE(new.title, ''),
                    COALESCE(new.album, ''),
                        COALESCE(new.tags, ''),
                        COALESCE(new.caption, '')
                    );
                END
            """.trimIndent()
        )
    }
}
