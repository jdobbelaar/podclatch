package com.podclatch.data.db

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

/** Placeholder entity so Room and schema export are wired; a later story replaces it with Feed and Episode. */
@Entity(tableName = "placeholder")
data class PlaceholderEntity(
    @PrimaryKey val id: Long = 0,
)

@Database(entities = [PlaceholderEntity::class], version = 1, exportSchema = true)
abstract class PodClatchDatabase : RoomDatabase()
