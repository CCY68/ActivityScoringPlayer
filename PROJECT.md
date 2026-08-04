# ActivityScoringPlayer — Google TV Player Demo（含手環管理 / Module A）

高齡運動 SIIR 補助案「動作分析模組」的 **Google TV 播放示範 App**，並收納 **Module A · 手環連線管理（DeviceModule）**。

> 本 repo 為多倉庫工作區的一部分。工作區全貌與跨元件契約見上層 [`../PROJECT.md`](../PROJECT.md)。
> DeviceModule 實作原理見 [`DeviceModule-Internal.md`](./DeviceModule-Internal.md)。

## 定位

- **平台**：Android / Google TV（Kotlin）。
- **角色**：整合示範 — 串接手環（Module A）、播放課程、對接評分引擎（Module B）輸出的示範前端。
- **Module A / DeviceModule**：透過 BLE 與健身手環通訊，將感測器原始訊號轉為結構化資料，分兩路轉送：
  - **健康數據**（HR/HRV/SpO2/體溫）**直送**上層 App 顯示；
  - **IMU DeviceFrame** 送 Module B 評分。

## DeviceModule 架構骨架

- **品牌適配器 + StateFlow 狀態機 + 指數退避重連**。
- 一般品牌走 `IBrandAdapter`（單一 characteristic、單包解析）；**DoctorOne B20** 走 `IFramedBrandAdapter`（請求/響應幀協議、跨包拼包）。
- B20 廣播辨識、幀格式、原始數據換算依 `../docs/B20*.pdf` 廠商文件。

## 注意事項

1. 健康與 IMU 走**獨立 listener**：App 只訂閱 Health，Module B 只訂閱 IMU。
2. IMU 原始值**不在 DeviceModule 內濾波**（訊號處理屬 Module B 職責），只負責傳輸。
3. B20 裝置端時間戳為「感測器重啟歸零的相對計數」，**不可**直接當 epoch 使用。
4. 斷線務必 `gatt.close()`（系統 GATT 連線槽上限 7）；UUID 比對統一轉大寫。
