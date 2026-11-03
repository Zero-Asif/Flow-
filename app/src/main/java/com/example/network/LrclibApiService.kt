package com.example.network

import com.example.network.model.LrclibResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface LrclibApiService {

    @GET("get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String? = null,
        @Query("duration") duration: Long? = null
    ): Response<LrclibResponse>

    @GET("search")
    suspend fun searchLyricsByTrackAndArtist(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String? = null
    ): Response<List<LrclibResponse>>

    @GET("search")
    suspend fun searchLyrics(
        @Query("q") query: String
    ): Response<List<LrclibResponse>>

    companion object {
        private const val BASE_URL = "https://lrclib.net/api/"

        fun create(): LrclibApiService {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "FlowLosslessAudio/2.0 (Android; Retrofit Audiophile Engine)")
                        .build()
                    chain.proceed(request)
                }
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(LrclibApiService::class.java)
        }
    }
}
