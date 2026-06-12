package com.socatv.nova.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.Series
import com.socatv.nova.data.model.VodStream
import com.socatv.nova.data.model.WatchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY num ASC")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE categoryId = :catId ORDER BY num ASC")
    fun getChannelsByCategory(catId: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' ORDER BY num ASC")
    suspend fun searchChannels(query: String): List<Channel>

    @Query("SELECT * FROM channels WHERE streamId IN (:ids)")
    suspend fun getChannelsByIds(ids: List<String>): List<Channel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Query("DELETE FROM channels")
    suspend fun clearAll()

    @Query("DELETE FROM channels WHERE streamId LIKE 'm3u_%'")
    suspend fun clearM3uChannels()
}

@Dao
interface VodDao {
    @Query("SELECT * FROM vod_streams ORDER BY num ASC")
    fun getAllVod(): Flow<List<VodStream>>

    @Query("SELECT * FROM vod_streams WHERE categoryId = :catId ORDER BY num ASC")
    fun getVodByCategory(catId: String): Flow<List<VodStream>>

    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%' ORDER BY num ASC")
    suspend fun searchVod(query: String): List<VodStream>

    @Query("SELECT * FROM vod_streams WHERE streamId IN (:ids)")
    suspend fun getVodByIds(ids: List<String>): List<VodStream>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVod(vod: List<VodStream>)

    @Query("DELETE FROM vod_streams")
    suspend fun clearAll()
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series_list ORDER BY name ASC")
    fun getAllSeries(): Flow<List<Series>>

    @Query("SELECT * FROM series_list WHERE categoryId = :catId ORDER BY name ASC")
    fun getSeriesByCategory(catId: String): Flow<List<Series>>

    @Query("SELECT * FROM series_list WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchSeries(query: String): List<Series>

    @Query("SELECT * FROM series_list WHERE seriesId IN (:ids)")
    suspend fun getSeriesByIds(ids: List<String>): List<Series>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: List<Series>)

    @Query("DELETE FROM series_list")
    suspend fun clearAll()
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY watchedAt DESC LIMIT 20")
    fun getContinueWatching(profileId: String): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 50")
    fun getAllHistory(): Flow<List<WatchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: WatchHistory)

    @Query("DELETE FROM watch_history WHERE contentId = :contentId")
    suspend fun remove(contentId: String)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)

    @Query("SELECT * FROM watch_history WHERE contentId = :contentId LIMIT 1")
    suspend fun get(contentId: String): WatchHistory?
}
