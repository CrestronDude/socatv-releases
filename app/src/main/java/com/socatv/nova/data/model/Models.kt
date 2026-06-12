package com.socatv.nova.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// ========== XTREAM API MODELS ==========

data class AuthResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    @SerializedName("username") val username: String?,
    @SerializedName("password") val password: String?,
    @SerializedName("auth") val auth: Int = 0,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("is_trial") val isTrial: String?,
    @SerializedName("active_cons") val activeCons: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("allowed_output_formats") val allowedOutputFormats: List<String>?
)

data class ServerInfo(
    @SerializedName("url") val url: String?,
    @SerializedName("port") val port: String?,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val serverProtocol: String?,
    @SerializedName("rtmp_port") val rtmpPort: String?,
    @SerializedName("timezone") val timezone: String?,
    @SerializedName("timestamp_now") val timestampNow: Long?,
    @SerializedName("time_now") val timeNow: String?
)

data class Category(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("parent_id") val parentId: Int = 0
)

@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey
    @SerializedName("stream_id") val streamId: String,
    @SerializedName("name") val name: String,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("tv_archive") val tvArchive: Int = 0,
    @SerializedName("direct_source") val directSource: String?,
    @SerializedName("tv_archive_duration") val tvArchiveDuration: Int = 0,
    @SerializedName("num") val num: Int = 0
)

@Entity(tableName = "vod_streams")
data class VodStream(
    @PrimaryKey
    @SerializedName("stream_id") val streamId: String,
    @SerializedName("name") val name: String,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5based: Double = 0.0,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("direct_source") val directSource: String?,
    @SerializedName("num") val num: Int = 0
)

@Entity(tableName = "series_list")
data class Series(
    @PrimaryKey
    @SerializedName("series_id") val seriesId: String,
    @SerializedName("name") val name: String,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("last_modified") val lastModified: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5based: Double = 0.0,
    @SerializedName("backdrop_path") val backdropPath: String?, // stored as comma-separated JSON
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("episode_run_time") val episodeRunTime: String?,
    @SerializedName("category_id") val categoryId: String?
)

data class EpgResponse(
    @SerializedName("epg_listings") val epgListings: List<EpgEntry>?
)

// Series detail (get_series_info)
data class SeriesInfoResponse(
    @SerializedName("info") val info: SeriesDetailInfo?,
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>?
)

data class SeriesDetailInfo(
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?
)

data class Episode(
    @SerializedName("id") val id: String?,
    @SerializedName("episode_num") val episodeNum: Int = 0,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: EpisodeInfo?,
    @SerializedName("added") val added: String?,
    @SerializedName("season") val season: Int = 1,
    @SerializedName("direct_source") val directSource: String?
) {
    val displayTitle: String get() = title ?: "Episode $episodeNum"
}

data class EpisodeInfo(
    @SerializedName("duration") val duration: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("release_date") val releaseDate: String?
)

data class EpgEntry(
    @SerializedName("id") val id: String?,
    @SerializedName("epg_id") val epgId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("lang") val lang: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("channel_id") val channelId: String?,
    @SerializedName("start_timestamp") val startTimestamp: Long?,
    @SerializedName("stop_timestamp") val stopTimestamp: Long?,
    @SerializedName("now_playing") val nowPlaying: Int = 0,
    @SerializedName("has_archive") val hasArchive: Int = 0
)

// ========== TMDB MODELS ==========

data class TmdbTrendingResponse(
    @SerializedName("page") val page: Int,
    @SerializedName("results") val results: List<TmdbItem>,
    @SerializedName("total_pages") val totalPages: Int,
    @SerializedName("total_results") val totalResults: Int
)

data class TmdbSearchResponse(
    @SerializedName("page") val page: Int,
    @SerializedName("results") val results: List<TmdbItem>,
    @SerializedName("total_pages") val totalPages: Int,
    @SerializedName("total_results") val totalResults: Int
)

data class TmdbItem(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("media_type") val mediaType: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("first_air_date") val firstAirDate: String?
) {
    val displayTitle: String get() = title ?: name ?: "Unknown"
}

data class TmdbEpisode(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("still_path") val stillPath: String?,
    @SerializedName("episode_number") val episodeNumber: Int = 0,
    @SerializedName("season_number") val seasonNumber: Int = 0
)

data class TmdbMovieResult(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("overview") val overview: String?
)

// ========== APP MODELS ==========

data class Profile(
    val id: String,
    val name: String,
    val avatarIndex: Int = 0,
    val isPinProtected: Boolean = false,
    val pin: String = "",
    val isKidsMode: Boolean = false,
    val accentColor: Int = 0xFF00DCFF.toInt()
)

@Entity(tableName = "watch_history")
data class WatchHistory(
    @PrimaryKey
    val contentId: String,
    val contentType: String, // "live", "vod", "series"
    val title: String,
    val thumbnailUrl: String?,
    val watchedAt: Long,
    val progressMs: Long = 0,
    val durationMs: Long = 0,
    val profileId: String = "default",
    val episodeInfo: String? = null // "S01E03" etc
)

data class Playlist(
    val id: String,
    val name: String,
    val items: List<PlaylistItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class PlaylistItem(
    val contentId: String,
    val contentType: String,
    val title: String,
    val thumbnailUrl: String?,
    val addedAt: Long = System.currentTimeMillis()
)

// Content type enum
enum class ContentType {
    LIVE, VOD, SERIES, RADIO, EPG, CATCHUP, MULTISCREEN, ALL, FAVORITES, ACCOUNT
}

// Mood
enum class Mood(val label: String, val emoji: String) {
    ACTION("Action", "💥"),
    COMEDY("Comedy", "😂"),
    ROMANCE("Romance", "❤️"),
    THRILLER("Thriller", "😱"),
    DOCUMENTARY("Documentary", "🎓"),
    KIDS("Kids", "🧸"),
    SPORTS("Sports", "⚽"),
    SURPRISE("Surprise Me", "🎲")
}

// Stream health snapshot
data class StreamHealth(
    val timestampMs: Long,
    val bitrateKbps: Int,
    val bufferMs: Long,
    val fps: Float,
    val droppedFrames: Int
)
