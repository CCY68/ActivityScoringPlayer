package com.johnson.fitness.http

import com.google.gson.GsonBuilder
import com.johnson.fitness.BuildConfig
import com.johnson.fitness.http.resource.IVideoResource
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object NetworkModule {

    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }
        // 防止 IOException（timeout/socket abort）在 Gson 解析期間逃逸到 ThreadPoolExecutor 造成 crash
        val dispatcherExecutor = ThreadPoolExecutor(
            0, Int.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue()
        ) { runnable ->
            Thread(runnable, "OkHttp Dispatcher").apply {
                isDaemon = false
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
            }
        }
        return OkHttpClient.Builder()
            .dispatcher(Dispatcher(dispatcherExecutor))
            .addInterceptor(RequestInterceptor())
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(10, 2, TimeUnit.MINUTES))
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun provideRetrofit(client: OkHttpClient): Retrofit {
        val gson = GsonBuilder()
            .registerTypeAdapter(BigDecimal::class.java, BigDecimalTypeAdapter())
            .create()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    fun provideVideoResource(retrofit: Retrofit): IVideoResource = retrofit.create(IVideoResource::class.java)
}
