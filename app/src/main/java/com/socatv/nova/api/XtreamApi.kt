package com.socatv.nova.api

import com.socatv.nova.data.model.AuthResponse
import com.socatv.nova.data.model.Category
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.EpgResponse
import com.socatv.nova.data.model.Series
import com.socatv.nova.data.model.VodStream
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

interface XtreamApi {

    @GET
    suspend fun authenticate(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<AuthResponse>

    @GET
    suspend fun getLiveCategories(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): Response<List<Category>>

    @GET
    suspend fun getLiveStreams(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String? = null
    ): Response<List<Channel>>

    @GET
    suspend fun getVodCategories(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): Response<List<Category>>

    @GET
    suspend fun getVodStreams(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null
    ): Response<List<VodStream>>

    @GET
    suspend fun getSeriesCategories(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): Response<List<Category>>

    @GET
    suspend fun getSeries(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null
    ): Response<List<Series>>

    @GET
    suspend fun getShortEpg(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: String,
        @Query("limit") limit: Int = 5
    ): Response<EpgResponse>

    @GET
    suspend fun getSeriesInfo(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: String
    ): Response<com.socatv.nova.data.model.SeriesInfoResponse>
}

object XtreamApiFactory {
    fun buildUrl(host: String): String {
        val base = if (host.startsWith("http")) host else "http://$host"
        return "$base/player_api.php"
    }

    fun buildStreamUrl(host: String, username: String, password: String, streamId: String): String {
        val base = if (host.startsWith("http")) host.trimEnd('/') else "http://${host.trimEnd('/')}"
        return "$base/live/$username/$password/$streamId.m3u8"
    }

    fun buildVodUrl(host: String, username: String, password: String, streamId: String, ext: String = "mp4"): String {
        val base = if (host.startsWith("http")) host.trimEnd('/') else "http://${host.trimEnd('/')}"
        val safeExt = ext.trimStart('.').ifBlank { "mp4" }
        return "$base/movie/$username/$password/$streamId.$safeExt"
    }

    fun buildSeriesEpisodeUrl(host: String, username: String, password: String, episodeId: String, ext: String): String {
        val base = if (host.startsWith("http")) host else "http://$host"
        val extension = if (ext.isBlank()) "mp4" else ext
        return "$base/series/$username/$password/$episodeId.$extension"
    }

    fun buildTimeshiftUrl(host: String, username: String, password: String,
                          streamId: String, durationMinutes: Int, startFormatted: String): String {
        val base = if (host.startsWith("http")) host.trimEnd('/') else "http://${host.trimEnd('/')}"
        return "$base/timeshift/$username/$password/$durationMinutes/$startFormatted/$streamId.ts"
    }
}
