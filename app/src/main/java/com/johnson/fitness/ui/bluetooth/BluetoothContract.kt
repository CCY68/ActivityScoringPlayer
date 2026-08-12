package com.johnson.fitness.ui.bluetooth

import com.fitness.device.model.B20DeviceInfo
import com.fitness.device.model.ConnectionState
import com.fitness.device.model.DeviceBrand
import com.fitness.device.model.DeviceType
import com.fitness.device.model.ImuSampleRate
import com.fitness.device.model.ImuSampleRateResult
import com.fitness.device.model.PpgData
import com.fitness.device.model.SignalQuality

data class BtDevice(
    val name: String?,
    val address: String,
    val rssi: Int = 0,
    val brand: DeviceBrand = DeviceBrand.UNKNOWN,
    val deviceType: DeviceType = DeviceType.OTHER,
    val signalQuality: SignalQuality = SignalQuality.LOST
)

data class BluetoothState(
    val devices: List<BtDevice> = emptyList(),
    val isScanning: Boolean = false,
    val bluetoothDisabled: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val batteryLevel: Int? = null,
    // B20 手環（幀式協議）專屬資料，其他品牌不會觸發，維持 null
    val b20DeviceInfo: B20DeviceInfo? = null,
    val latestPpg: PpgData? = null,
    // IMU 取樣頻率設定，見 IDeviceManager.setImuSampleRate()；null 代表尚未設定過
    val imuSampleRateResult: ImuSampleRateResult? = null,
    // 設定指令送出後到確認韌體是否生效為止（最多約 2 秒）為 true，期間 UI 停用選項避免重複觸發
    val isSettingImuSampleRate: Boolean = false
)

sealed class BluetoothIntent {
    object StartScan : BluetoothIntent()
    data class ConnectDevice(val device: BtDevice) : BluetoothIntent()
    object Disconnect : BluetoothIntent()
    /** 設定 IMU 取樣頻率（25/50/100 Hz），見 IDeviceManager.setImuSampleRate()。 */
    data class SetImuSampleRate(val rate: ImuSampleRate) : BluetoothIntent()
}

sealed class BluetoothEffect {
    object RequestPermission : BluetoothEffect()
    data class ShowConnectionError(val message: String) : BluetoothEffect()
}
