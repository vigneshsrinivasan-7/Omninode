package com.omninode.hub.di

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * NetworkModule — Hilt dependency injection module for all network-layer singletons.
 *
 * Provides:
 *  • [OkHttpClient]   — Shared HTTP client with logging interceptor
 *  • [Moshi]          — JSON serialization configured with Kotlin reflection adapter
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Provides the shared OkHttpClient used for both REST calls and WebSocket connections.
     *
     * Configuration:
     *  • 10s connect timeout — enough for local LAN HA instance
     *  • 0s read timeout    — WebSocket connections must never time out on read
     *  • 5s write timeout   — Prevent hung writes
     *  • 30s ping interval  — Keep-alive for WebSocket persistence
     *  • HttpLoggingInterceptor in BODY level for debug builds (stripped by ProGuard in release)
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provides the Moshi JSON serializer.
     *
     * Adapters registered:
     *  • KotlinJsonAdapterFactory — Enables Kotlin data class serialization with
     *    default parameter values and nullability support.
     *    Note: For production, consider switching to @JsonClass(generateAdapter = true)
     *    on all model classes and removing the reflection adapter to avoid R8 issues.
     */
    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }
}
