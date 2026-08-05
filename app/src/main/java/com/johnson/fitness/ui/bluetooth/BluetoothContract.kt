package com.johnson.fitness.ui.bluetooth

import com.fitness.device.model.B20DeviceInfo
import com.fitness.device.model.ConnectionState
import com.fitness.device.model.DeviceBrand
import com.fitness.device.model.DeviceType
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
    val latestPpg: PpgData? = null
)

sealed class BluetoothIntent {
    object StartScan : BluetoothIntent()
    data class ConnectDevice(val device: BtDevice) : BluetoothIntent()
    object Disconnect : BluetoothIntent()
}

sealed class BluetoothEffect {
    object RequestPermission : BluetoothEffect()
    data class ShowConnectionError(val message: String) : BluetoothEffect()
}
