package com.dom.samplenavigation.di

import com.dom.samplenavigation.api.telemetry.VehicleTelemetryApi
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelemetryModule {

    private const val BASE_URL = "https://wing-test.doldol.io/"

    @Provides
    @Singleton
    @Named("telemetry")
    fun provideTelemetryOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Timber.tag("TelemetryHttp").d(message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("telemetry")
    fun provideTelemetryRetrofit(@Named("telemetry") client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    fun provideVehicleTelemetryApi(@Named("telemetry") retrofit: Retrofit): VehicleTelemetryApi {
        return retrofit.create(VehicleTelemetryApi::class.java)
    }
}

