package com.johnson.fitness

import android.app.Application
import com.fitness.device.DeviceModule as FitnessDeviceModule
import com.fitness.device.api.IDeviceManager
import com.johnson.fitness.data.ScoringEngineFactory
import com.johnson.fitness.http.NetworkModule

class FitnessApp : Application() {

    val deviceManager: IDeviceManager by lazy {
        FitnessDeviceModule.builder(this).build()
    }

    val scoringEngineFactory: ScoringEngineFactory by lazy {
        ScoringEngineFactory(this)
    }

    val videoResource by lazy {
        val client = NetworkModule.provideOkHttpClient()
        val retrofit = NetworkModule.provideRetrofit(client)
        NetworkModule.provideVideoResource(retrofit)
    }
}
