package com.johnson.fitness.ui.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitness.device.api.IDeviceInfoListener
import com.fitness.device.api.IDeviceManager
import com.fitness.device.api.IPpgDataListener
import com.fitness.device.api.IScanListener
import com.fitness.device.model.B20DeviceInfo
import com.fitness.device.model.ConnectionState
import com.fitness.device.model.DeviceInfo
import com.fitness.device.model.PpgData
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class BluetoothViewModel(
    private val context: Context,
    val deviceManager: IDeviceManager
) : ViewModel() {

    private val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(BluetoothState())
    val state: StateFlow<BluetoothState> = _state.asStateFlow()

    private val _effect = Channel<BluetoothEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    // B20 手環（幀式協議）專屬 listener，其他品牌的裝置不會觸發回調
    private val ppgListener = object : IPpgDataListener {
        override fun onPpgData(data: PpgData) {
            _state.value = _state.value.copy(latestPpg = data)
        }
    }
    private val deviceInfoListener = object : IDeviceInfoListener {
        override fun onDeviceInfo(info: B20DeviceInfo) {
            _state.value = _state.value.copy(b20DeviceInfo = info)
        }
    }

    init {
        deviceManager.addPpgDataListener(ppgListener)
        deviceManager.addDeviceInfoListener(deviceInfoListener)
        viewModelScope.launch {
            deviceManager.discoveredDevices.collect { devices ->
                _state.value = _state.value.copy(devices = devices.map { it.toBtDevice() })
            }
        }
        viewModelScope.launch {
            deviceManager.connectionState.collect { connState ->
                _state.value = _state.value.copy(connectionState = connState)
                if (connState is ConnectionState.Error) {
                    _effect.send(BluetoothEffect.ShowConnectionError(connState.message))
                }
            }
        }
        viewModelScope.launch {
            deviceManager.batteryLevel.collect { level ->
                _state.value = _state.value.copy(batteryLevel = level)
            }
        }
    }

    fun onIntent(intent: BluetoothIntent) {
        when (intent) {
            BluetoothIntent.StartScan -> startScan()
            is BluetoothIntent.ConnectDevice -> connectDevice(intent.device)
            BluetoothIntent.Disconnect -> {
                deviceManager.disconnect()
                _state.value = _state.value.copy(
                    b20DeviceInfo = null,
                    latestPpg = null
                )
            }
        }
    }

    private fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(isScanning = false, bluetoothDisabled = true)
            return
        }
        _state.value = _state.value.copy(isScanning = true, bluetoothDisabled = false)
        deviceManager.startScan(object : IScanListener {
            override fun onDeviceFound(device: DeviceInfo) {}
            override fun onScanError(errorCode: Int, message: String) {
                _state.value = _state.value.copy(isScanning = false)
                viewModelScope.launch {
                    if (errorCode == -1) {
                        _effect.send(BluetoothEffect.RequestPermission)
                    } else {
                        _state.value = _state.value.copy(bluetoothDisabled = true)
                    }
                }
            }
            override fun onScanStopped() {
                _state.value = _state.value.copy(isScanning = false)
            }
        })
    }

    private fun connectDevice(device: BtDevice) {
        deviceManager.stopScan()
        val deviceInfo = DeviceInfo(
            name         = device.name,
            address      = device.address,
            rssi         = device.rssi,
            brand        = device.brand,
            deviceType   = device.deviceType,
            signalQuality = device.signalQuality
        )
        viewModelScope.launch {
            val success = deviceManager.connect(deviceInfo)
            if (!success) {
                _effect.send(BluetoothEffect.ShowConnectionError("連線失敗，請重試"))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        deviceManager.removePpgDataListener(ppgListener)
        deviceManager.removeDeviceInfoListener(deviceInfoListener)
        deviceManager.release()
    }

    private fun DeviceInfo.toBtDevice() = BtDevice(
        name          = name,
        address       = address,
        rssi          = rssi,
        brand         = brand,
        deviceType    = deviceType,
        signalQuality = signalQuality
    )
}
