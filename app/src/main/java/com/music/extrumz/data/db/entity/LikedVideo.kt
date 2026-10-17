package com.extrumz.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A song the user has liked, kept.
 *
 * Likes lived only in `LikeState`, a map in memory, so they lasted exactly as
 * long as the process did — and on this device the process does not last: MIUI
 * kills it with SwipeUpClean and OptimizationClean while the app is merely
 * backgrounded. Device logs over one day show four separate process starts,
 * and three kills the user never asked for.
 *
 * The display fields are stored rather than joined back to `tracks` because
 * most liked songs have no row there at all: a search result or a video has
 * never been scanned into this device's library, and there is nothing to join
 * to. Storing them is what lets one list hold both kinds.
 *
 * `sourceChatId`/`sourceMessageId` are the Telegram coordinates when there are
 * any, so a row can be played again without re-parsing the videoId.
 */
@Entity(tableName = "liked_videos")
data class LikedVideo(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationText: String?,
    val sourceChatId: Long,
    val sourceMessageId: Long,
    val likedAt: Long,
)
