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
import com.fitness.device.model.DeviceBrand
import com.fitness.device.model.DeviceInfo
import com.fitness.device.model.ImuSampleRate
import com.fitness.device.model.PpgData
import com.johnson.fitness.data.LastDevicePreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class BluetoothViewModel(
    private val context: Context,
    val deviceManager: IDeviceManager,
    private val lastDevicePreferences: LastDevicePreferences
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
                _state.value = _state.value.copy(devices = devices.filter { it.brand == DeviceBrand.B20 }.map { it.toBtDevice() })
            }
        }
        viewModelScope.launch {
            deviceManager.connectionState.collect { connState ->
                _state.value = _state.value.copy(connectionState = connState)
                if (connState is ConnectionState.Connected) {
                    // 記住這支裝置，下次進入播放頁才能直接自動連線（見 PlaybackViewModel.tryAutoConnectToLastDevice）
                    lastDevicePreferences.save(connState.device)
                }
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
                    latestPpg = null,
                    imuSampleRateResult = null,
                    isSettingImuSampleRate = false
                )
            }
            is BluetoothIntent.SetImuSampleRate -> setImuSampleRate(intent.rate)
        }
    }

    /**
     * 呼叫 IDeviceManager.setImuSampleRate()：韌體支援時直接設定，不支援（或未確認生效）
     * 時該方法內部會自動回退軟體端節流，這裡只需要把結果顯示出來即可，不需要自行判斷
     * 裝置能力。設定過程中（最多約 2 秒，等裝置回報確認）以 isSettingImuSampleRate
     * 停用畫面上的選項，避免使用者連續點擊觸發多次重疊的設定流程。
     */
    private fun setImuSampleRate(rate: ImuSampleRate) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSettingImuSampleRate = true)
            val result = deviceManager.setImuSampleRate(rate)
            _state.value = _state.value.copy(imuSampleRateResult = result, isSettingImuSampleRate = false)
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
        // deviceManager 是掛在 FitnessApp 的整個 App 共用單例（PlaybackViewModel 也靠它讀心率/IMU），
        // 不是這個 ViewModel 專屬的資源，離開這個畫面時只能收掉自己註冊的 listener，
        // 不能呼叫 release()——那會把底層藍牙連線斷掉、internal coroutine scope 也整個取消掉，
        // 導致離開配對頁之後裝置斷線、且之後永遠無法再重新掃描/連線（這正是先前回報的斷線問題）。
        deviceManager.removePpgDataListener(ppgListener)
        deviceManager.removeDeviceInfoListener(deviceInfoListener)
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
