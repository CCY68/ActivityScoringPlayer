package com.johnson.fitness.ui.playback

import com.fitness.device.api.IDeviceManager
import com.fitness.device.api.IHealthDataListener
import com.fitness.device.api.IImuDataListener
import com.fitness.device.model.HealthData
import com.fitness.device.model.ImuData
import com.fitness.activityscoringcore.api.IMotionDataProvider
import com.fitness.activityscoringcore.signal.RawImuSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 將 IDeviceManager 的 listener-based 回調轉換為 IMotionDataProvider 所需的 Flow。
 *
 * 新版 ScoringEngine 不會主動持有/訂閱 IMotionDataProvider（ADR 0020：engine 對硬體零依賴），
 * 這裡只負責裝置資料 → RawImuSample 的欄位轉換；呼叫端（PlaybackViewModel）需自行收集這兩條
 * Flow，並把時間戳換算到影片時間軸後轉呼叫 engine.submitImuSample()/submitHeartRateSample()。
 *
 * callbackFlow 在收集器取消時自動呼叫 awaitClose，確保 listener 一定被移除，不會造成記憶體洩漏。
 */
class MotionDataAdapter(
    private val deviceManager: IDeviceManager
) : IMotionDataProvider {

    override val imuStream: Flow<RawImuSample> = callbackFlow {
        val listener = object : IImuDataListener {
            override fun onImuData(data: ImuData) {
                trySend(data.toRawImuSample())
            }
        }
        deviceManager.addImuDataListener(listener)
        awaitClose { deviceManager.removeImuDataListener(listener) }
    }

    override val heartRateStream: Flow<Int> = callbackFlow {
        val listener = object : IHealthDataListener {
            override fun onHealthData(data: HealthData) {
                data.heartRate?.let { trySend(it) }
            }
        }
        deviceManager.addHealthDataListener(listener)
        awaitClose { deviceManager.removeHealthDataListener(listener) }
    }

    // HealthData.calories 是手環「當日累計卡路里」，不是本次課程消耗量；呼叫端需自行記錄
    // 課程開始當下的讀數再算差值。不屬於 IMotionDataProvider 契約，僅 App 端使用故獨立成員。
    val caloriesStream: Flow<Int> = callbackFlow {
        val listener = object : IHealthDataListener {
            override fun onHealthData(data: HealthData) {
                data.calories?.let { trySend(it) }
            }
        }
        deviceManager.addHealthDataListener(listener)
        awaitClose { deviceManager.removeHealthDataListener(listener) }
    }

    // HealthData.coreTemperatureC 是體核溫度（B20「0x05 實時數據 V2」temperature 欄位的
    // body 分量，已在 DeviceModule 還原為攝氏度；受環境與佩戴鬆緊影響，廠商文件註記僅供參考）。
    // 不屬於 IMotionDataProvider 契約，僅 App 端使用。
    val temperatureStream: Flow<Float> = callbackFlow {
        val listener = object : IHealthDataListener {
            override fun onHealthData(data: HealthData) {
                data.coreTemperatureC?.let { trySend(it) }
            }
        }
        deviceManager.addHealthDataListener(listener)
        awaitClose { deviceManager.removeHealthDataListener(listener) }
    }

    // packetId 原樣帶過去：SampleRateNormalizer 靠它偵測裝置感測器重啟（回捲）並重置 epoch。
    private fun ImuData.toRawImuSample() = RawImuSample(
        timestampMs = timestampMs,
        ax = ax, ay = ay, az = az,
        gx = gx, gy = gy, gz = gz,
        packetId = packetId
    )
}
