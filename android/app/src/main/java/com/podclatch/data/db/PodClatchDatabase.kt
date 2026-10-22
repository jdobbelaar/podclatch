package com.podclatch.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * Version 2 (US-11) adds the download progress columns. Later stories change no schema; if one ever must,
 * it adds a Migration. Never use fallbackToDestructiveMigration: it would destroy the outbox.
 */
@Database(
    entities = [Feed::class, Episode::class, PendingStateWrite::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PodClatchDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun pendingStateWriteDao(): PendingStateWriteDao
}

/** Version 1 to 2: the download progress columns; existing rows get 0 bytes, unknown total, not marked. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE episode ADD COLUMN bytesReceived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE episode ADD COLUMN totalBytes INTEGER")
        db.execSQL("ALTER TABLE episode ADD COLUMN downloadPermanentFailure INTEGER NOT NULL DEFAULT 0")
    }
}
