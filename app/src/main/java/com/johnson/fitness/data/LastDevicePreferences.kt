package com.johnson.fitness.data

import android.content.Context
import com.fitness.device.model.DeviceBrand
import com.fitness.device.model.DeviceInfo
import com.fitness.device.model.DeviceType

/**
 * 記住上次成功連線過的手環裝置，讓下次進入播放頁時可以直接自動連線，
 * 不必每次都先跳去「藍牙設定」畫面手動掃描配對（見 PlaybackViewModel.tryAutoConnectToLastDevice）。
 *
 * 只存 GATT 連線真正需要、且值得長期保存的欄位；`rssi`/`signalQuality` 是掃描當下的即時訊號，
 * 沒有持久化的意義，重新連線時一律回傳訊號值為 0 的預設值。
 */
class LastDevicePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 連線成功時呼叫；由 BluetoothViewModel 在觀察到 ConnectionState.Connected 時寫入。 */
    fun save(device: DeviceInfo) {
        prefs.edit()
            .putString(KEY_ADDRESS, device.address)
            .putString(KEY_NAME, device.name)
            .putString(KEY_BRAND, device.brand.name)
            .putString(KEY_DEVICE_TYPE, device.deviceType.name)
            .apply()
    }

    /** 沒有存過裝置（App 從未連線成功過，或使用者手動清除過資料）時回傳 null。 */
    fun load(): DeviceInfo? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val brand = prefs.getString(KEY_BRAND, null)?.let { runCatching { DeviceBrand.valueOf(it) }.getOrNull() }
            ?: DeviceBrand.UNKNOWN
        val deviceType = prefs.getString(KEY_DEVICE_TYPE, null)?.let { runCatching { DeviceType.valueOf(it) }.getOrNull() }
            ?: DeviceType.OTHER
        return DeviceInfo(
            name = prefs.getString(KEY_NAME, null),
            address = address,
            rssi = 0,
            brand = brand,
            deviceType = deviceType
        )
    }

    private companion object {
        const val PREFS_NAME = "last_device"
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_BRAND = "brand"
        const val KEY_DEVICE_TYPE = "device_type"
    }
}
