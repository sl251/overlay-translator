package com.gameocr.app.di

import com.gameocr.app.BuildConfig
import com.gameocr.app.ocr.OcrEngine
import com.gameocr.app.ocr.RoutingOcrEngine
import com.gameocr.app.translate.RoutingTranslator
import com.gameocr.app.translate.TranslationCache
import com.gameocr.app.translate.Translator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideTranslationCache(): TranslationCache = TranslationCache(capacity = 256)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindings {

    @Binds
    @Singleton
    abstract fun bindOcrEngine(impl: RoutingOcrEngine): OcrEngine

    @Binds
    @Singleton
    abstract fun bindTranslator(impl: RoutingTranslator): Translator
}
