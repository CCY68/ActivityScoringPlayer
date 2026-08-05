# DeviceModule 實作原理（內部工程師版）

> 本文件面向負責維護或擴展 DeviceModule 的工程師。  
> 第三方整合請閱讀 `DEVICE_MODULE_API.md`。

---

## 目錄

1. [系統全貌](#系統全貌)
2. [完整資料流](#完整資料流)
3. [BLE 掃描](#ble-掃描)
4. [GATT 連線流程](#gatt-連線流程)
5. [BLE Notification 啟用](#ble-notification-啟用)
6. [電池電量讀取](#電池電量讀取)
7. [資料路由與解析](#資料路由與解析)
8. [位元組格式：健康資料](#位元組格式健康資料)
9. [位元組格式：IMU 資料](#位元組格式imu-資料)
10. [品牌適配器層](#品牌適配器層)
11. [幀式協議裝置（B20）](#幀式協議裝置b20)
12. [連線狀態機](#連線狀態機)
13. [自動重連機制](#自動重連機制)
14. [執行緒模型與安全](#執行緒模型與安全)
15. [設計決策與取捨](#設計決策與取捨)
16. [已知限制與改進方向](#已知限制與改進方向)

---

## 系統全貌

DeviceModule 解決的核心問題：**透過 BLE（Bluetooth Low Energy）與健身手環通訊，將感測器原始訊號轉換為結構化資料，提供給 ScoringModule 評分或 UI 直接顯示。**

挑戰有四個：

1. **BLE 非同步性**：GATT 所有操作（掃描、連線、讀取、通知）都是非同步回調，生命週期管理複雜。
2. **裝置異質性**：不同品牌手環的 GATT UUID 和位元組格式各異，需要一個隔離變化的適配層。
3. **連線韌性**：BLE 連線在環境干擾下容易中斷，需要可設定的自動重連策略。
4. **協議形態異質性**：部分裝置（如 B20）不是「單一 characteristic 對應單一資料類型」的簡單模型，
   而是「請求/響應幀」協議，單一 characteristic 需承載多種資料、可能跨多包才組成完整幀。

解法：**品牌適配器 + StateFlow 狀態機 + 指數退避重連**，其中品牌適配器再依協議形態分為兩種：
`IBrandAdapter`（單一 characteristic、單包解析，Garmin/MiBand/Polar/Generic）與
`IFramedBrandAdapter`（幀式協議，B20，見「[幀式協議裝置（B20）](#幀式協議裝置b20)」）。
核心邏輯集中在 `BleDeviceManager`，品牌差異隔離在適配器層，狀態透過 `StateFlow` 響應式暴露給呼叫端。

---

## 完整資料流

```
使用者呼叫 startScan()
      │
      ▼
 ScanCallback.onScanResult()        接收 BLE 廣播封包
 BrandAdapterFactory.detectBrandFromScanRecord(scanRecord.bytes)  ← 優先：解析廣播封包辨識 B20
      未辨識出時 → BrandAdapterFactory.detectBrand(name)          ← 退回：從名稱推斷（GARMIN/GENERIC）
 BrandAdapterFactory.detectDeviceType()  推斷平台（GARMIN / WEAROS / OTHER）
 _discoveredDevices 去重後更新       StateFlow → UI 即時顯示掃描列表
 IScanListener.onDeviceFound()      ── BleDeviceManager.kt:onScanResult()
      │
      ▼（使用者呼叫 connect(device)）
 connectGatt()                      建立 GATT 連線
 currentAdapter = BrandAdapterFactory.create(device.brand)  ← B20 → B20Adapter()，其餘 → *Adapter()
 connectionState → Connecting
      │
      ▼（GATT 回調）
 onConnectionStateChange(CONNECTED)
 discoverServices()                 探索裝置服務清單
      │
      ▼
 onServicesDiscovered()
 enableNotifications()              一般品牌：健康 & IMU 各自寫入 CCCD；B20：僅 notifyCharUuid 寫入 CCCD
 readBatteryLevel()                 非同步讀取 Battery Service（0x180F）
 （僅 IFramedBrandAdapter）requestMtu(247) + startFramedProtocolSession()
      └─ 延遲 300ms → 送出 buildInitCommands()（時間同步/查詢裝置資訊/查詢並啟用感測器）
      └─ 啟動 framedPollJob：每 healthSampleIntervalMs 送出 buildPollCommands()（請求即時數據）
 connectionState → Connected
      │
      ▼（裝置主動推送，一般品牌）
 onCharacteristicChanged(uuid, value: ByteArray)
      ├── adapter.parseHealthData()  → IHealthDataListener.onHealthData(HealthData)
      ├── adapter.parseImuData()     → IImuDataListener.onImuData(ImuData)
      │   copy(sampleRateHz)         注入 config.imuSampleRateHz
      └── BATTERY_CHAR_UUID         → _batteryLevel StateFlow 更新

      ▼（裝置主動推送，幀式協議如 B20，見「幀式協議裝置（B20）」）
 onCharacteristicChanged(notifyCharUuid, value: ByteArray)
      └── adapter.consumeNotification(value)
           ├── B20FrameAssembler.feed()  拼包，湊齊完整幀才繼續
           ├── 依功能碼解析：0xB0(原始感測器)／0x85(即時數據)／0x9F(裝置資訊)
           └── List<SensorEvent> → dispatchSensorEvent() → 對應 Health/Imu/Ppg/DeviceInfo Listener
```

---

## BLE 掃描

**檔案**：`ble/BleDeviceManager.kt`

### 掃描去重

Android BLE 掃描結果可能在同一裝置廣播多次（每次間隔約 100–500ms）：

```kotlin
// BleDeviceManager.kt:90-93
val current = _discoveredDevices.value
if (current.none { it.address == info.address }) {
    _discoveredDevices.value = current + info
}
```

以 MAC address（`device.address`）去重，確保同一裝置只加入列表一次。每次 `startScan()` 呼叫會清空舊列表。

### 品牌與平台推斷

掃描時立即推斷品牌與平台，存入 `DeviceInfo`：

```kotlin
// BleDeviceManager.kt:onScanResult()
val brand = BrandAdapterFactory.detectBrandFromScanRecord(result.scanRecord?.bytes)
    ?: BrandAdapterFactory.detectBrand(deviceName)
val info = DeviceInfo(
    brand      = brand,
    deviceType = BrandAdapterFactory.detectDeviceType(deviceName),
    ...
)
```

`detectBrandFromScanRecord()` 優先於名稱式辨識執行，因為 B20 等幀式協議裝置的品牌辨識依據是廣播封包
中的自定義數據（見「[幀式協議裝置（B20）](#幀式協議裝置b20)」的「廣播封包辨識」小節），而非裝置名稱；
僅在回傳 `null`（不符合 B20 的合法性條件）時才退回既有的名稱關鍵字比對，避免影響其他品牌的既有行為。

`signalQuality` 不需要明確傳入，由 `DeviceInfo` 根據 `rssi` 自動計算（見[品牌適配器層](#品牌適配器層)）。

### 訊號品質判定

```kotlin
// model/SignalQuality.kt
GOOD  ← rssi ≥ -65 dBm   // 約 5 公尺以內且無障礙物
WEAK  ← rssi ∈ [-80, -65) // 邊界距離或有障礙物
LOST  ← rssi < -80 dBm 或 rssi = 0  // 幾乎不可靠
```

---

## GATT 連線流程

**檔案**：`ble/BleDeviceManager.kt`

BLE GATT 連線是一個多步驟的非同步流程，每一步都在 Android BLE 執行緒的回調中完成：

```
connect(device)
  └─ connectGatt()
       └─ [非同步] onConnectionStateChange(STATE_CONNECTED)
              └─ discoverServices()
                   └─ [非同步] onServicesDiscovered(GATT_SUCCESS)
                          ├─ enableNotifications()  ← 啟用 Health & IMU 通知
                          ├─ readBatteryLevel()     ← 非同步讀取電量
                          └─ connectionState → Connected
```

### API 版本相容性

`connectGatt()` 在 API 23+ 可指定傳輸方式：

```kotlin
// BleDeviceManager.kt:277-282
gattConnection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    btDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
} else {
    btDevice.connectGatt(context, false, gattCallback)
}
```

`TRANSPORT_LE` 明確指定 BLE 傳輸，避免裝置在 BLE 與 Classic Bluetooth 之間猶豫造成連線失敗。

### GATT 資源管理

斷線時必須呼叫 `gatt.close()`，否則消耗 Android 系統的 GATT 連線槽（上限 7 條）：

```kotlin
// BleDeviceManager.kt:120-121
try { gatt.close() } catch (_: Exception) { }
gattConnection = null
```

主動斷線（`disconnect()`）同樣呼叫 `close()`，確保資源完整釋放。

### `Connected` 狀態的 `DeviceInfo` 來源

`onServicesDiscovered()` 組成 `ConnectionState.Connected(info)` 時，`info` 依序取自：

1. `_discoveredDevices.value.find { it.address == address }`——當次掃描清單裡的最新資訊（含
   name/brand/rssi 等），一般手動掃描 + 連線的流程會走這條
2. `lastConnectedDevice`——`connect(device)` 呼叫當下傳入的 `DeviceInfo`；App 啟動時的自動重連
   （直接呼叫 `connect()`，未先 `startScan()`）掃描清單是空的，會落到這一層
3. `DeviceInfo(null, address, 0)`——以上兩者都拿不到時的最後 fallback，`name` 會是 `null`

第 2 層是修正實測時發現的問題：先前只有第 1、3 層，自動重連時掃描清單必為空，會直接落到
`name = null` 的 fallback，導致 UI 顯示「未知裝置」；即使呼叫端已經從持久化儲存還原出正確的
`name`/`brand` 傳給 `connect()`，也會在這裡被覆蓋掉。呼叫端若要在自動重連情境下正確顯示裝置
資訊，記得連同 `brand` 一併持久化（`brand` 決定 `BrandAdapterFactory.create()` 選到哪個
`IBrandAdapter`，光記 `address`/`name` 不夠，見「品牌適配器層」）。

---

## BLE Notification 啟用

**檔案**：`ble/BleDeviceManager.kt:enableNotifications()`

BLE Notification 啟用需要兩步驟，缺一不可：

```
步驟 1：setCharacteristicNotification(char, true)
        設定本地 GATT 快取，告知系統「我想接收通知」
        ↓ 但裝置端尚未知道

步驟 2：寫入 CCCD（Client Characteristic Configuration Descriptor）
        UUID：00002902-0000-1000-8000-00805f9b34fb（BLE 標準固定 UUID）
        值：0x0100（Little-Endian，表示 Enable Notification）
        ↓ 裝置收到後才開始推送資料
```

```kotlin
// BleDeviceManager.kt:342-357
gatt.setCharacteristicNotification(char, true)
val descriptor = char.getDescriptor(UUID.fromString("00002902-..."))
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
} else {
    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    gatt.writeDescriptor(descriptor)
}
```

API 33（TIRAMISU）後，`writeDescriptor()` 改為帶 `value` 參數的新版本；舊版需先設置 `.value` 欄位。兩條路徑都需維護。

---

## 電池電量讀取

**檔案**：`ble/BleDeviceManager.kt:readBatteryLevel()`

使用 BLE 標準 Battery Service（UUID `0x180F`），獨立於品牌 UUID 之外：

```kotlin
const val BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
const val BATTERY_CHAR_UUID    = "00002A19-0000-1000-8000-00805F9B34FB"
```

**讀取策略**：
1. 連線後立即 `readCharacteristic()`（一次性讀取，結果在 `onCharacteristicRead` 接收）
2. 若 Characteristic 支援 Notify，同時啟用通知，後續電量變化自動推送

**部分裝置不支援** Battery Service，`gatt.getService()` 回傳 `null` 時靜默忽略，`batteryLevel` 維持 `null`。

---

## 資料路由與解析

**檔案**：`ble/BleDeviceManager.kt:dispatchCharacteristic()`

`onCharacteristicChanged()` 接收所有 Characteristic 的通知，以 UUID 路由到不同處理路徑：

```kotlin
// BleDeviceManager.kt:dispatchCharacteristic()（UUID 統一轉大寫比對）
when (uuidStr) {
    BATTERY_CHAR_UUID → _batteryLevel.value 更新
}
// 幀式協議裝置（IFramedBrandAdapter）：單一 characteristic 承載多種資料，
// 不再依 UUID 分流資料種類，改由解析後的 SensorEvent 型別決定
if (adapter is IFramedBrandAdapter) {
    if (uuidStr == adapter.notifyCharUuid.uppercase()) {
        adapter.consumeNotification(value).forEach(::dispatchSensorEvent)
    }
    return
}
// 一般品牌（IBrandAdapter）：依 UUID 分流
when (uuidStr) {
    adapter.healthCharUuid.uppercase() → parseHealthData() → healthListeners
    adapter.imuCharUuid.uppercase()    → parseImuData().copy(sampleRateHz) → imuListeners
}
```

**UUID 大小寫正規化**：UUID 字串統一轉大寫後比對，避免裝置回傳小寫 UUID 造成匹配失敗（常見錯誤）。

**sampleRateHz 注入**：一般品牌的 `ImuData` 本身不含採樣率欄位值，由 `BleDeviceManager` 在路由時注入
`config.imuSampleRateHz`（預設 50）：

```kotlin
adapter.parseImuData(value)?.copy(sampleRateHz = config.imuSampleRateHz)
```

B20 則不套用此注入，`sampleRateHz` 由 `B20Adapter` 依連線後查詢到的實際感測器配置（`B20SensorConfig`）填入，
較 `DeviceConfig.imuSampleRateHz` 更準確反映裝置實際取樣率。

**SensorEvent 分派**：幀式協議裝置解析一個幀後可能產生零到多筆資料（例如一個 IMU 幀含多個樣本），
統一以 `SensorEvent` 表達後由 `dispatchSensorEvent()` 轉發：

```kotlin
private fun dispatchSensorEvent(event: SensorEvent) {
    when (event) {
        is SensorEvent.Health     -> healthListeners.forEach { it.onHealthData(event.data) }
        is SensorEvent.Imu        -> imuListeners.forEach { it.onImuData(event.data) }
        is SensorEvent.Ppg        -> ppgListeners.forEach { it.onPpgData(event.data) }
        is SensorEvent.DeviceInfo -> deviceInfoListeners.forEach { it.onDeviceInfo(event.data) }
    }
}
```

---

## 位元組格式：健康資料

**檔案**：`brand/GenericBrandAdapter.kt:parseHealthData()`

位元組流格式（Little-Endian，最少 5 bytes）：

```
Offset  Size  型別    縮放     說明
──────  ────  ──────  ──────   ──────────────────────────────
0       1     uint8   ×1       心率（BPM），直接讀取
1       1     uint8   ÷2       血氧（SpO2）原始值 → 百分比（0.5% 精度）
2–3     2     -       -        保留（忽略）
4–5     2     int16   ÷10      HRV → ms（0.1ms 精度）；封包 < 6 bytes 時為 null
```

**uint8 讀取的陷阱**：

```kotlin
// GenericBrandAdapter.kt:40-41
val hr   = buf.get().toInt() and 0xFF   // 必須 and 0xFF
val spo2 = (buf.get().toInt() and 0xFF) / 2
```

`ByteBuffer.get()` 回傳 `Byte`（有號，範圍 -128~127）。心率或血氧超過 127 時，直接 `.toInt()` 會變成負數。`and 0xFF` 強制轉為無號 0~255 範圍。

---

## 位元組格式：IMU 資料

**檔案**：`brand/GenericBrandAdapter.kt:parseImuData()`

位元組流格式（Little-Endian，最少 12 bytes）：

```
Offset  Size  型別    縮放     說明
──────  ────  ──────  ──────   ──────────────────────────────
0–1     2     int16   ÷1000    ax → m/s²
2–3     2     int16   ÷1000    ay → m/s²
4–5     2     int16   ÷1000    az → m/s²
6–7     2     int16   ÷10      gx → °/s
8–9     2     int16   ÷10      gy → °/s
10–11   2     int16   ÷10      gz → °/s
```

**縮放依據**：

```
加速度：量程 ±16g ≈ ±156.8 m/s²，int16 範圍 ±32768
        ÷1000 → ±32.7 m/s²（覆蓋大多數健身動作）

陀螺儀：量程 ±2000°/s，int16 ÷10 → ±3276.8°/s（覆蓋快速旋轉）
```

**`ImuData` 未填入的欄位**：磁力計（`mx/my/mz`）預設 `0f`；`timestampMs` 不從裝置讀取，由 `ImuData` 預設值 `System.currentTimeMillis()` 填入。

---

## 品牌適配器層

**檔案**：`brand/IBrandAdapter.kt`、`brand/BrandAdapterFactory.kt`、`brand/GenericBrandAdapter.kt`

### 介面設計

```kotlin
interface IBrandAdapter {
    val serviceUuid: String       // GATT Service UUID
    val healthCharUuid: String    // 健康資料 Characteristic UUID
    val imuCharUuid: String       // IMU 資料 Characteristic UUID
    fun parseHealthData(raw: ByteArray): HealthData?
    fun parseImuData(raw: ByteArray): ImuData?
    fun buildEnableNotifyCommand(): ByteArray? = null  // 部分品牌需額外初始化指令
}
```

### 品牌識別關鍵字

`BrandAdapterFactory.detectBrand()` 將裝置名稱轉小寫後關鍵字比對：

| 品牌 | 關鍵字 |
|------|--------|
| `GARMIN` | `"garmin"` |
| `GENERIC` | 其他有名稱的裝置（含小米手環、Polar 等，暫未細分品牌） |
| `UNKNOWN` | 名稱為 `null` |

> `DeviceBrand` 已移除 `MI_BAND`/`POLAR` 列舉值，這兩種裝置目前一律歸類為 `GENERIC`
> （`create()` 原本也只是把它們導向同一個 `GenericBrandAdapter()`，移除列舉值不影響既有行為，
> 只是不再假裝有區分品牌）；未來若這些品牌需要獨立的 UUID/解析格式，再依「新增品牌的步驟」補回。

`detectDeviceType()` 同樣關鍵字比對，判斷底層生態系：

| 平台 | 關鍵字 |
|------|--------|
| `GARMIN` | `"garmin"`、`"forerunner"`、`"fenix"`、`"venu"`、`"vivoactive"` |
| `WEAROS` | `"galaxy watch"`、`"pixel watch"`、`"ticwatch"`、`"fossil gen"`、`"wear os"` |
| `OTHER` | 其餘所有裝置 |

### 新增品牌的步驟

1. 在 `DeviceBrand.kt` 新增枚舉值
2. 實作 `IBrandAdapter`（UUID + 位元組解析），或若裝置為請求/響應幀協議，改實作 `IFramedBrandAdapter`
   （見「[幀式協議裝置（B20）](#幀式協議裝置b20)」）
3. 在 `BrandAdapterFactory.create()` 的 `when` 加入對應分支
4. 在 `BrandAdapterFactory.detectBrand()` 加入識別關鍵字；若無法以名稱辨識（如 B20 需解析廣播封包），
   改在 `detectBrandFromScanRecord()` 加入辨識邏輯，並讓 `BleDeviceManager.onScanResult()` 優先呼叫它

`GENERIC` 直接回傳 `GenericBrandAdapter()`；`GARMIN` 回傳 `GarminAdapter()`——目前只是
`GenericBrandAdapter` 的空殼子類別（沿用相同 UUID 與位元組格式，尚未 override 任何行為），
實際品牌差異尚未分支實作，但已預留獨立擴充點。`B20` 是目前唯一有獨立適配器實作（`B20Adapter`）、
且使用不同協議形態（`IFramedBrandAdapter`）的品牌。

---

## 幀式協議裝置（B20）

**檔案**：`brand/IFramedBrandAdapter.kt`、`brand/b20/{B20Protocol, B20FrameAssembler, B20SensorConfig,
B20AdvertisementParser, B20Adapter}.kt`

### 為何不直接擴充 IBrandAdapter

`IBrandAdapter` 的假設是「健康 / IMU 各自獨立 characteristic，且每次 `onCharacteristicChanged()`
剛好對應一筆完整、可直接解析的資料」。B20 的協議完全不符合這個假設：

- 服務 `0xFFF0` 下只有一個 notify characteristic（`0xFFF1`）承載**所有**資料類型（裝置資訊、即時數據、
  IMU/PPG 原始數據流），一個 write characteristic（`0xFFF2`）發送請求
- 資料以「請求/響應幀」為單位傳輸，單一幀最長 512Byte，遠超預設 BLE ATT MTU（23Byte），
  一個幀常需跨多次 `onCharacteristicChanged()` 才能收齊（拼包）
- 一個幀解析後可能對應零筆（如 ack）、一筆（如即時數據）或多筆（如 IMU 幀含多個取樣點）資料

若硬要塞進 `IBrandAdapter`（例如把 `healthCharUuid`/`imuCharUuid` 都設為 `0xFFF1`），`dispatchCharacteristic()`
原本以 UUID 相等比對分流的邏輯會退化：兩者相同時 `when` 只會命中第一個分支，IMU 資料永遠無法分派。
因此新增平行的 `IFramedBrandAdapter` 介面，`BleDeviceManager` 以 `currentAdapter is IFramedBrandAdapter`
判斷走哪一條路徑，兩者互不影響，既有品牌的行為零改動。

```kotlin
interface IFramedBrandAdapter : IBrandAdapter {
    val notifyCharUuid: String
    val writeCharUuid: String
    fun buildInitCommands(): List<ByteArray>
    fun buildPollCommands(): List<ByteArray> = emptyList()
    fun consumeNotification(raw: ByteArray): List<SensorEvent>
}
```

### 幀格式與校驗碼

```
包頭(1B, 0x68) + 功能碼(1B) + 載荷長度(2B, 低字節先傳) + 載荷實體(nB) + 校驗碼(1B) + 包尾(1B, 0x16)
```

- 校驗碼 = 包頭、功能碼、載荷長度、載荷實體所有位元組相加後取低 8 位（`B20Protocol.buildFrame()`）
- 功能碼 bit7 = 方向（0 主機→終端，1 終端→主機），bit6 = 異常位（`isFromDevice()` / `isError()`）
- 響應方向常見的載荷格式為「實體碼(1B) + JSON(UTF-8) + 0x00 結尾」，由
  `B20Protocol.entityJsonPayload()` / `parseEntityJsonPayload()` 組建與解析

### 幀重組（拼包）

**檔案**：`brand/b20/B20FrameAssembler.kt`

`B20FrameAssembler` 內部持有一個位元組緩衝區，`feed(chunk)` 每次呼叫執行：

1. 尋找緩衝區中的幀頭（`0x68`），捨棄之前的雜訊位元組（重新同步）
2. 若不足 4 bytes（幀頭+功能碼+長度），等待下一次 `feed()`
3. 讀出載荷長度，若總長度超出緩衝區現有大小，等待下一次 `feed()`（尚未收齊）
4. 收齊後驗證校驗碼與包尾（`0x16`）；驗證失敗則只捨棄 1 byte 後重新嘗試同步，
   避免單一損壞幀導致後續合法幀被整段丟棄
5. 驗證成功則產出 `ParsedFrame(funcCode, payload)`，並從緩衝區移除已消費的位元組，
   繼續迴圈嘗試解析下一幀（單次 `feed()` 可能同時吐出多個幀）

此類別非執行緒安全，需由呼叫端保證單一執行緒存取；`BleDeviceManager` 的 GATT 回調本身即為單一執行緒觸發。
每次 `connect()` 都會建立全新的 `B20Adapter`（進而是全新的 `B20FrameAssembler`），故不需要跨連線手動清空緩衝。

### 廣播封包辨識

**檔案**：`brand/b20/B20AdvertisementParser.kt`

B20 依廠商文件「B20藍芽廣播協議」規範，合法設備必須**同時**滿足：

1. 廣播數據：存在一個 `type=0xFF` 的 AD 結構，其值第一個位元組為 PackageID `0xB8`（自定義數據）
2. 掃描回應數據：存在一個 `type=0xFF` 的 AD 結構，其值以 PackageID `0xB6`（設備標識，2B：類型+型號）
   緊接 PackageID `0xB7`（MAC，6B，小端序）開頭

```kotlin
forEachAdStructure(scanRecordBytes) { type, value ->
    if (type != 0xFF || value.isEmpty()) return@forEachAdStructure
    when (value[0].toInt() and 0xFF) {
        0xB8 -> hasCustomDataPackage = true
        0xB6 -> { identifier = ...; if (value[3] == 0xB7) mac = ... }
    }
}
```

Android 主動掃描（active scan）收到 `SCAN_RSP` 時，`ScanResult.scanRecord` 通常已將廣播封包與掃描回應
封包合併回傳，因此對整段 `scanRecord.bytes` 一次掃描即可涵蓋兩個條件，不需區分來源封包。
B20 的設備標識為類型 `0x08` / 型號 `0x40`（`B20AdvertisementParser.B20_IDENTIFIER`），
`parseB20()` 在型號不符或任一條件不滿足時回傳 `null`。

### MTU 協商與寫入流程

幀最長 512Byte，預設 ATT MTU（23Byte，扣除 3Byte 標頭僅剩 20Byte 可用）遠不足以承載，因此
`onServicesDiscovered()` 對幀式協議裝置額外呼叫 `gatt.requestMtu(247)`（best-effort，失敗僅略過不阻塞流程）。

MTU 協商結果不透過回調同步初始化流程（避免不同裝置/OS 版本的 `onMtuChanged()` 行為不一致造成初始化
永遠不觸發），而是固定延遲 300ms 後直接送出 `buildInitCommands()`：

```kotlin
private fun startFramedProtocolSession(gatt: BluetoothGatt, adapter: IFramedBrandAdapter) {
    framedPollJob = scope.launch {
        delay(300)
        adapter.buildInitCommands().forEach { frame -> writeFrame(gatt, adapter, frame) }
        while (isActive) {
            delay(config.healthSampleIntervalMs)
            adapter.buildPollCommands().forEach { frame -> writeFrame(gatt, adapter, frame) }
        }
    }
}
```

`writeFrame()` 依 API 版本選擇 `BluetoothGatt.writeCharacteristic()` 的新舊簽名，寫入類型固定為
`WRITE_TYPE_DEFAULT`（需等待裝置回應）。

**寫入序列化**：`writeFrame()` 是 `suspend fun`，內部以 `Mutex` 序列化——同一時間僅送出一筆、
等待 `onCharacteristicWrite` 回呼觸發（`CompletableDeferred<Boolean>`，逾時 `WRITE_TIMEOUT_MS`
= 2000ms 視為失敗但不中斷流程）才送下一筆。這是修正真實裝置測試時發現的問題：`buildInitCommands()`
以 `forEach` 背靠背連續呼叫 `writeCharacteristic()`（先前實作未等待完成回呼），Android BLE 堆疊
在前一筆寫入尚未完成前再次呼叫容易靜默失敗（回傳 false 但未檢查），導致 4 筆初始化幀中最後一筆
「啟用感測器」（`ENTITY_SENSOR_CONFIG_SET`）沒有真正送達裝置，症狀是連線成功但 IMU/PPG
從未收到任何資料。目前 `writeFrame()` 只被 `buildInitCommands()`/`buildPollCommands()` 呼叫；
先前心率／血氧預警（0x0A）曾透過另一個 `sendFramedCommand()` 私有方法共用同一把鎖送出任意時刻的
指令，該功能已改為 App 端自行判斷（見「心率／血氧預警」章節），`sendFramedCommand()` 隨之移除，
若未來又有「連線後任意時刻觸發送出一幀」的需求，可參考當時的設計：共用同一個 `writeMutex`，
避免跟 `buildInitCommands()`/`buildPollCommands()` 彼此搶佔佇列。

### 輪詢機制（即時數據無主動推送）

廠商文件「0x05 - 實時數據」只定義了 App 主動請求、終端回應一次的模式，並無裝置端主動推送機制。
因此 `B20Adapter.buildPollCommands()` 回傳「請求即時數據」幀，由 `BleDeviceManager` 依
`DeviceConfig.healthSampleIntervalMs` 週期性送出並寫入，結果透過既有的 `IHealthDataListener` 回報。

### 連線初始化內容

`B20Adapter.buildInitCommands()` 依序組建：

1. 時間同步（功能碼 `0x04`，實體 `0x01`）：目前系統時間（epoch 秒）與時區偏移
2. 查詢裝置資訊（功能碼 `0x1F`，實體 `0x01`）：回應觸發 `SensorEvent.DeviceInfo`
3. 查詢感測器配置（功能碼 `0x30`，實體 `0x01`）：回應更新 `B20SensorConfig`（IMU/PPG 的
   取樣率、精度、量程），供 IMU 原始數據換算使用
4. 啟用全部感測器（功能碼 `0x30`，實體 `0x02`）：IMU `ctrl=0x07`（三軸全開）、PPG `ctrl=0x07`
   （三通道全開）

查詢感測器配置與啟用感測器兩者皆走相同功能碼 `0x30`（響應固定為 `0xB0`），僅實體碼不同，
與廠商文件「B20原始數據文件」的定義一致。

### 原始數據流解析（IMU/PPG）

**檔案**：`brand/b20/B20Adapter.kt`

兩種串流的響應皆為功能碼 `0xB0`，以實體碼 `0x81`/`0x82` 區分，共用 header：

```
實體碼(1B) + 旗標(1B) + 封包ID(4B LE) + 裝置端時間戳(4B LE) + 樣本序列
```

- 旗標位元決定該幀實際包含哪些通道（如 IMU 的 Acce/Gyro/Mag、PPG 的 Green/Red/IR），
  順序固定（Acce>Gyro>Mag、Green>Red>IR），依旗標動態計算單一樣本的位元組長度
- **裝置端時間戳未使用**：其語意是「感測器重新開啟時歸零的相對計數」，並非 wall-clock 時間，
  若直接填入 `timestampMs`（其餘欄位皆為 epoch 毫秒）會造成語意不一致，因此改以
  `System.currentTimeMillis()`（接收當下時間）為基準，依樣本在幀內的序號與查詢到的取樣率
  （`1000 / rateHz`）推算每筆樣本的近似時間戳
- IMU 換算：`raw × range × 係數 / ((1 << (depth-1)) - 1)`（Acce 係數為重力加速度 9.81，
  Gyro 係數為 `π/180` 換算為 rad/s），换算所需的 `range`/`depth` 來自查詢到的 `B20SensorConfig`，
  查詢完成前使用廠商文件範例值作為保守預設（`B20SensorConfig.DEFAULT_IMU`）
- PPG：廠商文件未提供轉換公式，保留為原始計數值（`readInt24LE()` 讀取為有號整數）

### HRV 資訊解析

**檔案**：`brand/b20/B20Adapter.kt` `handleRealtimeHealthFrame()`

`0x05` 實時數據回應的 `hrv` 欄位（`"SDNN,TP,LF,HF,VLF"`，協議原始值 x1000）與既有 `bp`/
`battery`/`basic_data` 欄位一樣，以 CSV 字串 `split(",")` + `getOrNull(i)?.trim()?.toXxxOrNull()`
的既有慣例解析，五個數值須全部成功解析才會組成 `HrvData`（任一缺漏則整體為 `null`，不做部分填入），
最終掛在 `HealthData.hrvDetail`。

`HealthData.hrv: Float?` 這個既有單一數值欄位（原為 `GenericBrandAdapter` 等非幀式協議品牌設計）
會回填 `hrvDetail.sdnn`——SDNN 是 HRV 最常用的單一數值代表指標，單位同為 ms，讓不同品牌都能透過
同一欄位取得「一個」HRV 數值；需要完整五維指標時仍應讀取 `hrvDetail`。`hrvDetail` 為 `null`
（五個數值任一缺漏）時 `hrv` 也隨之為 `null`，兩者狀態一致。

### 心率／血氧預警（App 端自行判斷，2026-08-05 取代協議層 0x0A 實作）

**檔案**：`ble/BleDeviceManager.kt`、`api/IHealthWarningListener.kt`、`model/HealthMetricType.kt`

原本的實作走裝置協議 0x0A（`B20Adapter.buildSetWarningFrame()`/`buildReadWarningFrame()`），
App 設定門檻＋週期交給裝置判斷；裝置不會主動推播「已觸發」事件，只把事件寫進自己的「預警記錄」
（0x31/0x32/0x33），App 端還要另外輪詢讀取——這整套（`IWarningListener`/`WarningConfig`/
`WarningType`/`WarningRecord`，以及 `B20Adapter` 對應的幀建構/解析、`BleDeviceManager` 的
`sendFramedCommand()` 私有方法）**已整個移除**，改成完全在 App 端（`BleDeviceManager` 內部）
自行判斷，不再依賴任何裝置協議：

```kotlin
// BleDeviceManager 內部欄位
private val healthWarningListeners = CopyOnWriteArrayList<IHealthWarningListener>()
@Volatile private var heartRateWarningRange: IntRange? = null
@Volatile private var spo2WarningRange: IntRange? = null

private fun checkHealthWarning(data: HealthData) {
    data.heartRate?.let { hr ->
        heartRateWarningRange?.let { range ->
            if (hr !in range) {
                healthWarningListeners.forEach { it.onHealthWarning(HealthMetricType.HEART_RATE, hr, range) }
            }
        }
    }
    data.spo2?.let { spo2 ->
        spo2WarningRange?.let { range ->
            if (spo2 !in range) {
                healthWarningListeners.forEach { it.onHealthWarning(HealthMetricType.SPO2, spo2, range) }
            }
        }
    }
}
```

`checkHealthWarning()` 掛在既有的兩個 `HealthData` 分派點之後（一般品牌的 `dispatchCharacteristic()`
與幀式協議裝置的 `dispatchSensorEvent()`），跟 `healthListeners.forEach { it.onHealthData(data) }`
同一個位置各呼叫一次——**判斷邏輯純粹是 App 端對已經解析好的 `HealthData` 做範圍比對，跟裝置是
哪個品牌、走哪種協議完全無關**，這也是這次改版比舊版好維護的地方：舊版的 0x0A 預警是 B20 專屬
（協議層功能），新版對所有品牌一視同仁。

範圍用 Kotlin 內建的 `IntRange`（`min..max`）表達，不另外定義 config 資料類別——`IDeviceManager
.setHeartRateWarningRange(range: IntRange?)`/`setSpo2WarningRange(range: IntRange?)` 直接寫入上述
`@Volatile` 欄位，`null` 代表取消該類型的預警（`checkHealthWarning()` 對應的 `?.let` 就不會執行）。
沒有「fire-and-forget 送出指令、等裝置 ACK」這種非同步往返，設定即時生效，下一筆 `HealthData`
就會用新範圍判斷。

`release()` 會清空 `healthWarningListeners`（跟其他 listener 列表一致），但**不會**重置
`heartRateWarningRange`/`spo2WarningRange`——`BleDeviceManager` 實例通常隨 ViewModel 生命週期
一起建立/銷毀，重置範圍目前不是必要行為，如未來有「同一個 manager 實例跨裝置重用」的情境，
需重新評估這個決定。

---

## 連線狀態機

**檔案**：`model/ConnectionState.kt`

以 `sealed class` 實作有限狀態機，確保所有狀態在 `when` 表達式中被完整處理：

```
Disconnected ──connect()──▶ Connecting ──GATT 回調──▶ Connected
     ▲                                                      │
     │                                          非預期斷線   │
     │                                                      ▼
     │                                          Reconnecting(attempt, max)
     │                                        ╱（重連成功）  ╲（超過次數）
     │                                   Connecting             Error
     └──────────── disconnect() / release() ──────────────────────┘
```

| 狀態 | 觸發條件 |
|------|---------|
| `Disconnected` | 初始狀態；`disconnect()` / `release()` 呼叫後 |
| `Connecting` | `connect()` 呼叫後；重連等待後再次嘗試時 |
| `Connected` | `onServicesDiscovered(GATT_SUCCESS)` 且品牌 Service 找到 |
| `Reconnecting` | 非預期斷線後，`scheduleReconnect()` 啟動 |
| `Error` | 重連次數耗盡；`SecurityException`（權限不足） |

---

## 自動重連機制

**檔案**：`ble/BleDeviceManager.kt:scheduleReconnect()`

### 觸發條件

`@Volatile var intentionalDisconnect = false`：主動呼叫 `disconnect()` 時設為 `true`，使 `onConnectionStateChange(STATE_DISCONNECTED)` 不觸發重連：

```kotlin
// BleDeviceManager.kt:122-128
if (intentionalDisconnect) {
    _connectionState.value = ConnectionState.Disconnected
} else {
    scheduleReconnect()   // 非預期斷線才重連
}
```

### 指數退避公式

```
delay = min(reconnectBaseDelayMs × 2^(attempt-1), 30_000ms)

reconnectBaseDelayMs = 1000ms（預設）：
  第 1 次：1000 × 2⁰ = 1s
  第 2 次：1000 × 2¹ = 2s
  第 3 次：1000 × 2² = 4s
  第 N 次：上限 30s
```

```kotlin
// BleDeviceManager.kt:259-262
val delayMs = minOf(
    config.reconnectBaseDelayMs * (1L shl (reconnectAttempt - 1)),
    30_000L
)
```

`1L shl (attempt - 1)` 等同 `2^(attempt-1)`，使用位元移位避免 `Math.pow()` 的浮點轉換。

### 重連協程管理

重連使用內部 `CoroutineScope(Dispatchers.IO + SupervisorJob())`，與呼叫端生命週期解耦。`reconnectJob` 保存當前重連協程的引用，`disconnect()` 或 `release()` 呼叫時取消：

```kotlin
reconnectJob?.cancel()   // 取消等待中的重連
reconnectAttempt = 0     // 重置計數
```

---

## 執行緒模型與安全

### 執行緒來源

| 回調 | 執行緒 | 說明 |
|------|--------|------|
| `ScanCallback.onScanResult()` | BLE 執行緒（非主） | Android 系統分派 |
| `BluetoothGattCallback.*` | BLE 執行緒（非主） | Android 系統分派 |
| `scheduleReconnect()` 內部 delay | `Dispatchers.IO` | 內部協程 |
| `StateFlow` 更新 | 任意（MutableStateFlow 執行緒安全） | 可從任意執行緒寫入 |

### `intentionalDisconnect` 的 `@Volatile`

```kotlin
@Volatile private var intentionalDisconnect = false
```

`disconnect()` 從主執行緒（或 ViewModel 任意協程）寫入，`onConnectionStateChange` 從 BLE 執行緒讀取。`@Volatile` 確保寫入立即對所有執行緒可見，避免 CPU 快取導致舊值被讀取。

### 監聽器的執行緒

`healthListeners` 和 `imuListeners` 是普通 `mutableListOf()`，非執行緒安全。目前只在 BLE 執行緒（`dispatchCharacteristic`）讀取，在主執行緒（`addHealthDataListener`）寫入。若未來多執行緒同時操作，需改為 `CopyOnWriteArrayList`。

---

## 設計決策與取捨

### 1. StateFlow 而非回調介面

**決策**：`connectionState`、`discoveredDevices`、`batteryLevel` 以 `StateFlow` 暴露，而非 listener 介面。  
**理由**：`StateFlow` 與 Jetpack Compose 的 `collectAsStateWithLifecycle()` 原生整合，自動處理生命週期感知，呼叫端不需手動 add/remove 監聽器。  
**代價**：感測器資料（Health / IMU）仍用 listener 介面，因為高頻資料（50 Hz）若透過 StateFlow 每幀更新，會對 Compose recompose 造成壓力。

### 2. 品牌適配器多數仍回傳 GenericBrandAdapter

**決策**：`BrandAdapterFactory.create()` 目前僅 `B20` 有獨立協議實作（`B20Adapter`，`IFramedBrandAdapter`）；
`GARMIN` 回傳 `GarminAdapter()`（`GenericBrandAdapter` 的空殼子類別，尚未 override 任何行為）；
其餘一律回傳 `GenericBrandAdapter()`。  
**理由**：目前只有 B20 是實際串接的測試裝置，其餘品牌尚無真實裝置可驗證 UUID/位元組格式差異，
`detectBrand()` 也因此只保留 `"garmin"` 關鍵字比對，其餘一律歸類 `GENERIC`（已移除 `MI_BAND`/`POLAR`
枚舉值，見「品牌識別關鍵字」）。  
**代價**：`GarminAdapter`/`GenericBrandAdapter` 行為目前完全相同，`DeviceBrand.GARMIN` 只是預留的
擴充點，尚無實質區分。接入真實 Garmin 或其他品牌裝置時，需在對應 Adapter override UUID/解析邏輯，
並視需要於 `DeviceBrand.kt`／`detectBrand()` 補回專屬列舉值與關鍵字。

### 3. 不在 DeviceModule 內做訊號濾波

**決策**：IMU 原始值（含重力、高頻雜訊）直接轉發，不在 DeviceModule 內過濾。  
**理由**：訊號處理屬於評分邏輯範疇，由 ScoringModule 的 `MotionFilter`（IIR 低通）負責。DeviceModule 只負責傳輸，確保單一職責。  
**代價**：呼叫端若不搭配 ScoringModule，直接使用原始 IMU 資料時需自行過濾。

### 4. 指數退避上限 30 秒

**決策**：重連等待時間上限為 30 秒，而非無限增長。  
**理由**：BLE 裝置（手環）通常在使用者可操作範圍內，30 秒的最長間隔不會讓使用者等待過久，又足以讓 BLE 環境恢復穩定。  
**代價**：若裝置長時間離開範圍（> `maxReconnectAttempts × 30s`），進入 `Error` 狀態後不再重試，需使用者重新手動掃描連線。

---

## 已知限制與改進方向

### 品牌支援

| 項目 | 現況 | 改進方向 |
|------|------|---------|
| 品牌識別（Garmin/MiBand/Polar/Generic） | 僅依賴廣播名稱關鍵字比對 | 可改用 Company ID（廣播封包 Manufacturer Specific Data）更精確識別；B20 已改用廣播封包自定義數據辨識，可作為範本 |

### 幀式協議裝置（B20）

| 項目 | 現況 | 改進方向 |
|------|------|---------|
| 即時數據更新機制 | 無裝置端主動推送，以 `healthSampleIntervalMs` 輪詢請求 | 若裝置韌體未來支援主動推送，可改為訂閱式減少無謂輪詢流量 |
| 寫入指令長度 | 假設協商後的 MTU 足以容納單次寫入的完整幀（`writeCharacteristic()` 單次呼叫，未實作長寫入/分段） | 若未來指令載荷可能超過協商 MTU，需改用 BLE Long Write（Prepare Write Request 佇列） |
| MTU 協商時機 | `requestMtu()` 為 best-effort，未等待 `onMtuChanged()` 結果即固定延遲 300ms 後送出初始化幀 | 若目標裝置對協商時序敏感，可改為等待 `onMtuChanged()` 回調再送出，並加上逾時保護 |
| 幀重組錯誤處理 | 校驗碼或包尾不符時僅捨棄 1 byte 嘗試重新同步，無記錄/告警機制 | 可加入損壞幀計數與日誌，利於現場除錯與訊號品質評估 |
| 封包連續性 | `packetId` 已隨 IMU/PPG 資料回報，但呼叫端需自行比對是否跳號 | 可在 DeviceModule 內建跳號偵測與告警，減少每個呼叫端重複實作 |
| PPG 物理量換算 | 廠商文件未提供換算公式，維持原始計數值 | 待廠商補充換算公式或現場校正參數後再實作 |


