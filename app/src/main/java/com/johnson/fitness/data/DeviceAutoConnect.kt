package com.johnson.fitness.data

import android.content.Context
import com.fitness.device.api.IDeviceManager
import com.fitness.device.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 自動連線到上次成功連線過的裝置。App 啟動時（[com.johnson.fitness.FitnessApp]）與進入播放頁時
 * （PlaybackViewModel）都會呼叫這個共用邏輯，讓使用者不必每次都手動跳去「藍牙設定」畫面配對。
 *
 * 條件都成立才會真的嘗試連線：目前沒有連線中/已連線、藍牙權限已授予、且有存檔裝置。
 * 連線本身是 fire-and-forget——成功/失敗都會反映在 [IDeviceManager.connectionState]，
 * 呼叫端沿用既有的 connectionState 監聽顯示結果即可，這裡不用等待、也不回傳結果。
 */
object DeviceAutoConnect {

    fun tryConnect(
        scope: CoroutineScope,
        context: Context,
        deviceManager: IDeviceManager,
        preferences: LastDevicePreferences
    ) {
        if (deviceManager.connectionState.value != ConnectionState.Disconnected) return
        if (!BlePermissions.isGranted(context)) return
        val savedDevice = preferences.load() ?: return

        scope.launch { deviceManager.connect(savedDevice) }
    }
}
