package com.socatv.nova.utils

import android.util.LruCache
import com.socatv.nova.api.TMDbApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory cache + fetcher for TMDb backdrop images.
 * Keyed by content title so multiple calls for the same title hit cache.
 * Empty-string sentinel means "searched, nothing found" — prevents repeat API calls.
 */
object TmdbImageManager {

    private val backdropCache = LruCache<String, String>(500)
    private val showIdCache   = LruCache<String, Int>(200)
    private val stillCache    = LruCache<String, String>(300)

    private val api by lazy { TMDbApi.create() }

    // ── Backdrop (16:9) for VOD / Series cards ────────────────────────────

    suspend fun getBackdrop(title: String, isMovie: Boolean = true): String? =
        withContext(Dispatchers.IO) {
            val key = "${if (isMovie) "m" else "tv"}:${title.lowercase().trim()}"
            backdropCache[key]?.let { return@withContext it.ifBlank { null } }

            runCatching {
                val resp = if (isMovie) api.searchMovie(title) else api.searchTv(title)
                val items = resp.body()?.results ?: emptyList()
                val best  = items.firstOrNull { !it.backdropPath.isNullOrBlank() }
                    ?: items.firstOrNull()
                val url = TMDbApi.imageUrl(best?.backdropPath) ?: ""
                backdropCache.put(key, url)
                url.ifBlank { null }
            }.getOrElse {
                backdropCache.put(key, "")
                null
            }
        }

    // Try movie first, then TV — useful when we don't know the type
    suspend fun getBackdropAny(title: String): String? =
        getBackdrop(title, isMovie = true) ?: getBackdrop(title, isMovie = false)

    // ── Episode stills for Series detail screen ───────────────────────────

    suspend fun getEpisodeStill(showTitle: String, season: Int, episodeNum: Int): String? =
        withContext(Dispatchers.IO) {
            val stillKey = "ep:${showTitle.lowercase()}:s${season}e${episodeNum}"
            stillCache[stillKey]?.let { return@withContext it.ifBlank { null } }

            runCatching {
                val tvId = getOrFetchShowId(showTitle) ?: run {
                    stillCache.put(stillKey, ""); return@withContext null
                }
                val resp = api.getEpisode(tvId, season, episodeNum)
                val path = resp.body()?.stillPath
                val url  = if (!path.isNullOrBlank()) "${TMDbApi.STILL_BASE}$path" else ""
                stillCache.put(stillKey, url)
                url.ifBlank { null }
            }.getOrElse {
                stillCache.put(stillKey, "")
                null
            }
        }

    // ── Series backdrop (for SeriesDetailActivity header) ─────────────────

    suspend fun getSeriesBackdrop(showTitle: String): String? =
        withContext(Dispatchers.IO) {
            val key = "tv:${showTitle.lowercase().trim()}"
            backdropCache[key]?.let { return@withContext it.ifBlank { null } }

            runCatching {
                val resp = api.searchTv(showTitle)
                val items = resp.body()?.results ?: emptyList()
                val best  = items.firstOrNull { !it.backdropPath.isNullOrBlank() }
                val url   = TMDbApi.imageUrl(best?.backdropPath) ?: ""
                backdropCache.put(key, url)
                url.ifBlank { null }
            }.getOrElse {
                backdropCache.put(key, "")
                null
            }
        }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun getOrFetchShowId(showTitle: String): Int? {
        showIdCache[showTitle.lowercase()]?.let { return it }
        return runCatching {
            val resp = api.searchTv(showTitle)
            val id   = resp.body()?.results?.firstOrNull()?.id ?: return null
            showIdCache.put(showTitle.lowercase(), id)
            id
        }.getOrNull()
    }

    fun clearCache() {
        backdropCache.evictAll()
        showIdCache.evictAll()
        stillCache.evictAll()
    }
}
