package com.podclatch.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/*
 * Version 1 is kept: the US-01 placeholder database never shipped. Later stories bump the version and
 * add a Migration. Never use fallbackToDestructiveMigration: it would destroy the outbox.
 */
@Database(
    entities = [Feed::class, Episode::class, PendingStateWrite::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PodClatchDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun pendingStateWriteDao(): PendingStateWriteDao
}
