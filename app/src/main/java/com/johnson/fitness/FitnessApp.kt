package com.johnson.fitness

import android.app.Application
import com.fitness.device.DeviceModule as FitnessDeviceModule
import com.fitness.device.api.IDeviceManager
import com.johnson.fitness.data.DeviceAutoConnect
import com.johnson.fitness.data.LastDevicePreferences
import com.johnson.fitness.data.ScoringEngineFactory
import com.johnson.fitness.http.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FitnessApp : Application() {

    // 只用來跑「App 啟動時自動連線」這種跟著整個 process 存活、不屬於任何畫面的背景工作，
    // 不是給業務邏輯共用的一般用途 scope。
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val deviceManager: IDeviceManager by lazy {
        FitnessDeviceModule.builder(this).build()
    }

    val lastDevicePreferences: LastDevicePreferences by lazy {
        LastDevicePreferences(this)
    }

    val scoringEngineFactory: ScoringEngineFactory by lazy {
        ScoringEngineFactory(this)
    }

    val videoResource by lazy {
        val client = NetworkModule.provideOkHttpClient()
        val retrofit = NetworkModule.provideRetrofit(client)
        NetworkModule.provideVideoResource(retrofit)
    }

    override fun onCreate() {
        super.onCreate()
        // App 進程一啟動就嘗試連回上次連線過的手環，不用等使用者進到播放頁或藍牙設定畫面。
        // 沒有存檔裝置、沒有藍牙權限、或已經在連線中/已連線，DeviceAutoConnect 內部會直接跳過。
        DeviceAutoConnect.tryConnect(appScope, this, deviceManager, lastDevicePreferences)
    }
}
