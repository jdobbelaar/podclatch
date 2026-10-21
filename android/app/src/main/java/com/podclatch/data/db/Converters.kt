package com.podclatch.data.db

import androidx.room.TypeConverter

/** Enums are stored as their names (TEXT). */
class Converters {
    @TypeConverter
    fun listenOrderToString(value: ListenOrder): String = value.name

    @TypeConverter
    fun stringToListenOrder(value: String): ListenOrder = ListenOrder.valueOf(value)

    @TypeConverter
    fun episodeStatusToString(value: EpisodeStatus): String = value.name

    @TypeConverter
    fun stringToEpisodeStatus(value: String): EpisodeStatus = EpisodeStatus.valueOf(value)

    @TypeConverter
    fun downloadStateToString(value: DownloadState): String = value.name

    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState = DownloadState.valueOf(value)
}
