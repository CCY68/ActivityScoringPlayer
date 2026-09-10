# ActivityScoringPlayer — Scoring Demo Player

高齡運動 SIIR 補助案「動作分析模組」的 **Google TV 示範 App**：把評分引擎與手環連線兩個函式庫
接起來，示範「播課程 → 收手環資料 → 即時評分 → 課後成果卡」的完整流程。

> 本 repo 為多倉庫工作區的一部分。工作區全貌與跨元件契約見上層 [`../PROJECT.md`](../PROJECT.md)。

## 定位

- **平台**：Android / Google TV（Kotlin、Compose for TV）。
- **組成**：**2 個 AAR ＋ 使用範例**——
  - `activity-scoring-core.aar`（Module B 評分引擎，來源 repo `ActivityScoringCore`；已內含 MAF 解析／解密，
    不再需要獨立的 `maf-format.jar`）；
  - `device-module.aar`（Module A 手環連線，來源 repo `WtivityDeviceModule`）；
  - 本 repo 只寫**範例程式**：播放、時間軸換算、HUD、成果卡、CSV 錄製與回放。
- **不含**：評分演算法、BLE 實作、會員後端。課程目錄是 `app/src/main/assets/courses.json`（60 支課程），
  `.maf` 課程檔與內容金鑰打包在 `app/src/main/assets/`；唯一呼叫的 API 是 Welltivity 的**免驗證**
  課程資訊端點（`open/course/info`），只用來查影片播放網址。

## 注意事項

1. 兩個 AAR **不會自動同步**：來源 repo 改動後要重新建置並手動複製到 `app/libs/`，
   同時更新 `ActivityScoringCore-Integration.md` 的版本表（日期／commit／大小／sha256 前 16 碼）。
2. 健康與 IMU 走**獨立 listener**：App 只訂閱 Health（直送畫面），評分引擎只訂閱 IMU。
3. 送進引擎的 IMU 時戳一律是**影片時間**，由 B20 裝置端時鐘換算（`data/ImuVideoTimeline.kt`）；
   丟樣以**缺口**進 Core，不補樣本。
4. 心率**不進入動作分數**，只用於強度區間與熱量估算；生理參數在 `設定 → 使用者資料`
   （`data/UserProfilePreferences.kt`），未設定時是標示清楚的 Demo 預設值。
5. 對外聲明限「心率與運動強度監看」，不得涉及醫療／診斷／急救。
6. `DeviceModule-Internal.md` 是 DeviceModule 獨立成 repo 之前留下的**歷史文件**，
   內部原理以 `WtivityDeviceModule` repo 的文件為唯一來源。
