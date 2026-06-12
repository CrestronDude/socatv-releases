package com.socatv.nova.data.repository

import android.util.Log
import com.socatv.nova.api.TMDbApi
import com.socatv.nova.api.XtreamApi
import com.socatv.nova.api.XtreamApiFactory
import com.socatv.nova.data.db.NovaDatabase
import com.socatv.nova.data.model.*
import com.socatv.nova.utils.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.socatv.nova.utils.NovaGson
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class IptvRepository(private val db: NovaDatabase) {

    private val TAG = "IptvRepository"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (compatible)")
                .header("Accept", "application/json, */*")
                .build())
        }
        .build()

    private val gsonConverter = GsonConverterFactory.create(NovaGson.instance)

    private val xtreamApi: XtreamApi by lazy {
        Retrofit.Builder()
            .baseUrl("http://hostengine.live/")
            .client(httpClient)
            .addConverterFactory(gsonConverter)
            .build()
            .create(XtreamApi::class.java)
    }

    private val tmdbApi: TMDbApi by lazy { TMDbApi.create() }

    // ========== AUTH ==========

    suspend fun authenticate(host: String, username: String, password: String): Result<AuthResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanHost = if (host.startsWith("http")) host.trimEnd('/') else "http://$host".trimEnd('/')
                val api = buildApi(cleanHost)
                val url = "$cleanHost/player_api.php"
                val response = api.authenticate(url, username, password)
                if (response.isSuccessful) {
                    val body = response.body()
                    val userInfo = body?.userInfo
                    when {
                        userInfo == null -> Result.failure(Exception("Invalid response from server"))
                        userInfo.auth != 1 -> Result.failure(Exception("Invalid username or password"))
                        userInfo.status != "Active" -> Result.failure(Exception("Account ${userInfo.status ?: "inactive"}"))
                        else -> Result.success(body)
                    }
                } else {
                    Result.failure(Exception("Server error: ${response.code()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auth error", e)
                Result.failure(Exception("Cannot connect to server: ${e.message}"))
            }
        }
    }

    private fun buildApi(baseUrl: String): XtreamApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(httpClient)
            .addConverterFactory(gsonConverter)
            .build()
            .create(XtreamApi::class.java)
    }

    // ========== LIVE TV ==========

    suspend fun getLiveCategories(): Result<List<Category>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getLiveCategories(url, user, pass)
                if (response.isSuccessful) {
                    Result.success(response.body() ?: emptyList())
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getLiveStreams(categoryId: String? = null): Result<List<Channel>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getLiveStreams(url, user, pass, categoryId = categoryId)
                if (response.isSuccessful) {
                    val channels = response.body() ?: emptyList()
                    db.channelDao().insertChannels(channels)
                    Result.success(channels)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "getLiveStreams error", e)
                Result.failure(e)
            }
        }
    }

    fun getLiveStreamsFlow(categoryId: String) = db.channelDao().getChannelsByCategory(categoryId)

    // ========== VOD ==========

    suspend fun getVodCategories(): Result<List<Category>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getVodCategories(url, user, pass)
                if (response.isSuccessful) {
                    Result.success(response.body() ?: emptyList())
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getVodStreams(categoryId: String? = null): Result<List<VodStream>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getVodStreams(url, user, pass, categoryId = categoryId)
                if (response.isSuccessful) {
                    val vod = response.body() ?: emptyList()
                    db.vodDao().insertVod(vod)
                    Result.success(vod)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ========== SERIES ==========

    suspend fun getSeriesCategories(): Result<List<Category>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getSeriesCategories(url, user, pass)
                if (response.isSuccessful) {
                    Result.success(response.body() ?: emptyList())
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getSeries(categoryId: String? = null): Result<List<Series>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getSeries(url, user, pass, categoryId = categoryId)
                if (response.isSuccessful) {
                    val series = response.body() ?: emptyList()
                    db.seriesDao().insertSeries(series)
                    Result.success(series)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ========== RADIO ==========

    suspend fun getRadioCategories(): Result<List<Category>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getLiveCategories(url, user, pass)
                if (response.isSuccessful) {
                    val all = response.body() ?: emptyList()
                    val radioKws = listOf("radio", "music", "fm", "am", "station", "audio")
                    val filtered = all.filter { cat ->
                        radioKws.any { kw -> cat.categoryName.lowercase().contains(kw) }
                    }
                    Result.success(if (filtered.isNotEmpty()) filtered else all)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ========== CATCH-UP ==========

    suspend fun getCatchupChannels(categoryId: String? = null): Result<List<Channel>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getLiveStreams(url, user, pass, categoryId = categoryId)
                if (response.isSuccessful) {
                    val channels = (response.body() ?: emptyList()).filter { it.tvArchive == 1 }
                    Result.success(channels)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun buildTimeshiftUrl(streamId: String, durationMinutes: Int, startEpochSec: Long): String {
        val (host, user, pass) = getCredentials()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val startFormatted = sdf.format(java.util.Date(startEpochSec * 1000))
        return XtreamApiFactory.buildTimeshiftUrl(host, user, pass, streamId, durationMinutes, startFormatted)
    }

    // ========== FAVORITES ==========

    suspend fun getFavoriteItems(): Triple<List<Channel>, List<VodStream>, List<Series>> {
        return withContext(Dispatchers.IO) {
            val ids = Prefs.getFavoriteIds()
            if (ids.isEmpty()) return@withContext Triple(emptyList(), emptyList(), emptyList())
            Triple(
                db.channelDao().getChannelsByIds(ids),
                db.vodDao().getVodByIds(ids),
                db.seriesDao().getSeriesByIds(ids)
            )
        }
    }

    // ========== EPG ==========

    suspend fun getShortEpg(streamId: String): Result<List<EpgEntry>> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getShortEpg(url, user, pass, streamId = streamId)
                if (response.isSuccessful) {
                    Result.success(response.body()?.epgListings ?: emptyList())
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ========== TMDB ==========

    suspend fun getTrending(): Result<List<TmdbItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = tmdbApi.getTrending()
                if (response.isSuccessful) {
                    Result.success(response.body()?.results ?: emptyList())
                } else {
                    Result.failure(Exception("TMDb error: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun searchTmdb(query: String): Result<List<TmdbItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val movieResp = tmdbApi.searchMovie(query = query)
                val tvResp = tmdbApi.searchTv(query = query)
                val movies = movieResp.body()?.results ?: emptyList()
                val tv = tvResp.body()?.results ?: emptyList()
                Result.success((movies + tv).sortedByDescending { it.voteAverage })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getTrendingBackdrop(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val resp = tmdbApi.getTrending()
                val item = resp.body()?.results?.firstOrNull { it.backdropPath != null }
                TMDbApi.imageUrl(item?.backdropPath)
            } catch (e: Exception) {
                null
            }
        }
    }

    // ========== WATCH HISTORY ==========

    fun getContinueWatching(profileId: String) = db.watchHistoryDao().getContinueWatching(profileId)

    suspend fun updateWatchHistory(history: WatchHistory) {
        db.watchHistoryDao().upsert(history)
    }

    suspend fun removeFromHistory(contentId: String) {
        db.watchHistoryDao().remove(contentId)
    }

    // ========== SEARCH ==========

    suspend fun searchAll(query: String): Triple<List<Channel>, List<VodStream>, List<Series>> {
        return withContext(Dispatchers.IO) {
            Triple(
                db.channelDao().searchChannels(query),
                db.vodDao().searchVod(query),
                db.seriesDao().searchSeries(query)
            )
        }
    }

    // ========== HELPERS ==========

    private fun getCredentials(): Triple<String, String, String> {
        val host = Prefs.serverUrl.ifBlank { "http://hostengine.live" }
        val user = Prefs.username
        val pass = Prefs.password
        return Triple(host, user, pass)
    }

    fun buildStreamUrl(streamId: String): String {
        val (host, user, pass) = getCredentials()
        return XtreamApiFactory.buildStreamUrl(host, user, pass, streamId)
    }

    fun buildVodUrl(streamId: String, ext: String = "mp4"): String {
        val (host, user, pass) = getCredentials()
        return XtreamApiFactory.buildVodUrl(host, user, pass, streamId, ext)
    }

    fun buildEpisodeUrl(episodeId: String, ext: String): String {
        val (host, user, pass) = getCredentials()
        return XtreamApiFactory.buildSeriesEpisodeUrl(host, user, pass, episodeId, ext)
    }

    suspend fun searchChannels(query: String): List<com.socatv.nova.data.model.Channel> =
        withContext(Dispatchers.IO) { db.channelDao().searchChannels(query) }

    suspend fun searchVod(query: String): List<com.socatv.nova.data.model.VodStream> =
        withContext(Dispatchers.IO) { db.vodDao().searchVod(query) }

    suspend fun searchSeries(query: String): List<com.socatv.nova.data.model.Series> =
        withContext(Dispatchers.IO) { db.seriesDao().searchSeries(query) }

    suspend fun getSeriesInfo(seriesId: String): Result<com.socatv.nova.data.model.SeriesInfoResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val (host, user, pass) = getCredentials()
                val url = XtreamApiFactory.buildUrl(host)
                val response = xtreamApi.getSeriesInfo(url, user, pass, seriesId = seriesId)
                if (response.isSuccessful) {
                    Result.success(response.body() ?: com.socatv.nova.data.model.SeriesInfoResponse(null, null))
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
