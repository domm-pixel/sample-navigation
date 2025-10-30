package com.dom.samplenavigation.di

import com.dom.samplenavigation.BuildConfig
import com.dom.samplenavigation.api.a.NullOnEmptyConverterFactory
import com.dom.samplenavigation.api.navigation.NaverDirectionApi
import com.dom.samplenavigation.api.navigation.NaverMapApi
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
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
object NaverModule {

    @Provides
    @Singleton
    @Named("naverMap")
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                if (!message.startsWith("{") && !message.startsWith("[")) {
                    Timber.tag("OkHttp").d(message)
                    return
                }
                try {
                    // Timber 와 Gson setPrettyPrinting 를 이용해 json 을 보기 편하게 표시해준다.
                    Timber.tag("OkHttp").d(
                        GsonBuilder().setPrettyPrinting().create().toJson(
                            JsonParser.parseString(message)
                        )
                    )
                } catch (m: JsonSyntaxException) {
                    Timber.tag("OkHttp").d(message)
                }
            }
        }).apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Provides
    @Singleton
    @Named("naverMap")
    fun provideNaverMapRetrofit(@Named("naverMap") client: OkHttpClient): Retrofit {
        val naverClient = client.newBuilder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                println("🔑 [NaverMap] API Key Debug - CLIENT_ID: ${BuildConfig.NAVER_MAP_CLIENT_ID}")
                println("🔑 [NaverMap] API Key Debug - API_KEY: ${BuildConfig.NAVER_MAP_API_KEY}")
                println("🌐 [NaverMap] Request URL: ${originalRequest.url}")
                
                val req = originalRequest.newBuilder()
                    .addHeader("x-ncp-apigw-api-key-id", BuildConfig.NAVER_MAP_CLIENT_ID)
                    .addHeader("x-ncp-apigw-api-key", BuildConfig.NAVER_MAP_API_KEY)
                    .build()
                
                println("📤 [NaverMap] Headers added - KEY-ID: ${req.header("X-NCP-APIGW-API-KEY-ID")}")
                println("📤 [NaverMap] Headers added - API-KEY: ${req.header("X-NCP-APIGW-API-KEY")}")
                
                chain.proceed(req)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("https://maps.apigw.ntruss.com/") // Naver Map reverse geocode API Base URL
            .addConverterFactory(NullOnEmptyConverterFactory())     // response값이 empty일 경우 처리하는 Converter
            .addConverterFactory(GsonConverterFactory.create())
            .client(naverClient) // Retrofit에 OkHttpClient 연결 (헤더 주입 클라이언트)
            .build()
    }

    @Provides
    @Singleton
    @Named("naverDirection")
    fun provideNaverDirectionRetrofit(@Named("naverMap") client: OkHttpClient): Retrofit {
        val naverClient = client.newBuilder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                println("🔑 [NaverDirection] API Key Debug - CLIENT_ID: ${BuildConfig.NAVER_MAP_CLIENT_ID}")
                println("🔑 [NaverDirection] API Key Debug - API_KEY: ${BuildConfig.NAVER_MAP_API_KEY}")
                println("🌐 [NaverDirection] Request URL: ${originalRequest.url}")
                
                val req = originalRequest.newBuilder()
                    .addHeader("x-ncp-apigw-api-key-id", BuildConfig.NAVER_MAP_CLIENT_ID)
                    .addHeader("x-ncp-apigw-api-key", BuildConfig.NAVER_MAP_API_KEY)
                    .build()
                
                println("📤 [NaverDirection] Headers added - KEY-ID: ${req.header("X-NCP-APIGW-API-KEY-ID")}")
                println("📤 [NaverDirection] Headers added - API-KEY: ${req.header("X-NCP-APIGW-API-KEY")}")
                
                chain.proceed(req)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("https://maps.apigw.ntruss.com/") // Naver Direction API Base URL
            .addConverterFactory(NullOnEmptyConverterFactory())
            .addConverterFactory(GsonConverterFactory.create())
            .client(naverClient)
            .build()
    }

    @Provides
    @Singleton
    fun provideNaverMapApi(@Named("naverMap") naverMapRetrofit: Retrofit): NaverMapApi {
        return naverMapRetrofit.create(NaverMapApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNaverDirectionApi(@Named("naverDirection") naverDirectionRetrofit: Retrofit): NaverDirectionApi {
        return naverDirectionRetrofit.create(NaverDirectionApi::class.java)
    }
}