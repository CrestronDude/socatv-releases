package com.socatv.nova.api

import com.socatv.nova.data.model.TmdbEpisode
import com.socatv.nova.data.model.TmdbMovieResult
import com.socatv.nova.data.model.TmdbSearchResponse
import com.socatv.nova.data.model.TmdbTrendingResponse
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface TMDbApi {

    @GET("trending/all/week")
    suspend fun getTrending(
        @Query("language") language: String = "en-US"
    ): Response<TmdbTrendingResponse>

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>

    @GET("trending/movie/week")
    suspend fun getTrendingMovies(): Response<TmdbSearchResponse>

    @GET("trending/tv/week")
    suspend fun getTrendingTv(): Response<TmdbSearchResponse>

    @GET("tv/{tv_id}/season/{season}/episode/{episode}")
    suspend fun getEpisode(
        @retrofit2.http.Path("tv_id") tvId: Int,
        @retrofit2.http.Path("season") season: Int,
        @retrofit2.http.Path("episode") episode: Int
    ): Response<TmdbEpisode>

    companion object {
        const val BEARER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiZTQ1ZDczZDI0MGI2ZTdmYmMxZGEwYTEyNzljMzcwNSIsIm5iZiI6MTc4MTE4NTEzMy41MDMsInN1YiI6IjZhMmFiYTZkNjBmYTBlOTdiNjU2OWMzOCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.B4bFgQ3iFisRfOSB6pP8aV3Xs3b6BBLquXUm4lYMgcE"
        const val BASE_URL    = "https://api.themoviedb.org/3/"
        const val IMAGE_BASE  = "https://image.tmdb.org/t/p/w1280"
        const val THUMB_BASE  = "https://image.tmdb.org/t/p/w500"
        const val STILL_BASE  = "https://image.tmdb.org/t/p/w400"

        fun imageUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return "$IMAGE_BASE$path"
        }

        fun thumbUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return "$THUMB_BASE$path"
        }

        fun create(): TMDbApi {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", "Bearer $BEARER_TOKEN")
                        .header("accept", "application/json")
                        .build()
                    chain.proceed(request)
                }
                .build()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TMDbApi::class.java)
        }
    }
}
