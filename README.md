# ActivityScoringPlayer

Google TV 播放示範 App，內含 **Module A · 手環連線管理（DeviceModule）**。串接健身手環、播放課程影片，並示範對接評分引擎（Module B）的整合流程。

- **平台**：Android / Google TV（Kotlin）
- **主硬體**：DoctorOne B20（BLE，單腕 IMU + PPG）
- **DeviceModule**：BLE 連線、品牌適配、幀式協議、自動重連

## 文件

- [`DeviceModule-Internal.md`](./DeviceModule-Internal.md) — DeviceModule 內部實作原理
- [`ActivityScoringCore-Integration.md`](./ActivityScoringCore-Integration.md) — Core AAR 內建 MAF 解析與 `.maf` 課程檔使用方法
- [`PROJECT.md`](./PROJECT.md) — 本元件定位與注意事項
- [`AGENTS.md`](./AGENTS.md) — AI 協作守則
- [`../PROJECT.md`](../PROJECT.md) — 工作區全貌與跨元件契約

## 資料流

```
手環(B20, BLE) → DeviceModule
   ├─ 健康數據（HR/HRV/SpO2/體溫）── 直送 ──▶ App 顯示
   └─ IMU DeviceFrame ─────────────────────▶ Module B 評分
```

DeviceModule 以「品牌適配器 + StateFlow 狀態機 + 指數退避重連」為骨架：一般品牌走 `IBrandAdapter`，B20 走 `IFramedBrandAdapter`（幀式請求/響應協議）。

## 全程靜坐錄製流程

Core 的評分回歸測試需要一份**真的坐著不動**的 IMU 錄製當零分錨（Core `docs/評分修復更新計畫_v1_20260907.md`
決策 B3；素材放在 Core `testdata/imu/`）。名義上「戴著錶看影片」的錄製實測有三成時間在動，不能用，
因此錄製時請照下列流程做。

**前置**

1. B20 充飽電、戴在慣用手腕上（鬆緊以不滑動為準），開機。
2. App 先到藍牙設定頁完成連線一次（之後會自動連回上次的裝置）。
3. 找一張有靠背的椅子；錄製全程手腕放在大腿或扶手上不動。

**錄製**

1. 首頁選課程 →詳情頁按播放 →「選擇播放模式」選 **正常模式（B20）**。
2. 「是否收錄 B20 IMU？」選 **收錄 CSV**。
3. 進入播放頁後**按播放鍵開始播放**——播放器預設是暫停狀態，**沒有在播放就不會寫入任何樣本**
   （CSV 的時間戳是影片時間）。
4. 確認手環狀態是「已連線」、畫面上有「● CSV 收錄中」，且 IMU 樣本數持續增加後再開始靜坐。
5. **全程靜止至少 5 分鐘**：不跟著比劃、不滑手機、不調整手環、不用手勢說話。
6. **不要 seek、不要暫停**。兩者都會重錨 IMU 時間軸（見下節）；**往回拉尤其不行**——CSV 只有一欄
   `timestamp_ms`，讀取時會整份依它排序，同一段影片錄到兩遍就會交錯成無意義的序列。
7. 結束方式二選一：
   - **看完整堂課**：影片播完 3 秒後自動結束收錄，跳出「收錄完成」對話框顯示檔名。
   - **提前離開**（錄夠 5 分鐘就好）：按返回鍵。CSV 會被正確收尾寫檔，但**不會跳出對話框**，
     檔名要自己到下載目錄找最新的那支。

**取檔與交付**

- Android 10 以上：`Download/ActivityScoringPlayer/<yyyy-MM-ddTHH:mm:ss.SSS>.csv`；
  更舊的版本落在 App 專屬的 Documents 目錄。
- 取回：`adb pull /sdcard/Download/ActivityScoringPlayer/`。
- 改名成 `<受測者>-still-<課程>-<原時間>.csv` 後放進 Core `testdata/imu/`，並在該目錄的 `README.md`
  表格補上筆數、時長、用途。**CSV 本身不加任何檔頭註解**（保持純資料；DeviceModule／Player 版本
  記在 Core `testdata/imu/README.md`）。

**欄位格式**

```
timestamp_ms,ax,ay,az,gx,gy,gz
```

`timestamp_ms` 是**影片時間軸**（見下節），`ax/ay/az` 單位 m/s²、`gx/gy/gz` 單位 rad/s。
本 App 的「Replay CSV」模式可直接回放同一份檔案。

**自我驗收**

錄完用 Replay CSV 模式回放同一支課程：HUD 應該整段顯示「等待動作」而不是給分。
角速度大於 0.3 rad/s 的樣本比例若超過一成，代表動太多，重錄。

## IMU 時間軸

送進評分引擎的每筆 IMU 樣本、以及錄製 CSV 的 `timestamp_ms`，都是**影片時間**（ms）：

```
videoTimeMs = (deviceTimestampUs − 錨點裝置時戳) / 1000 + 錨點影片位置
```

`deviceTimestampUs` 是 DeviceModule 從 B20 幀頭取得的**裝置端時鐘**（µs），不受 BLE 送達延遲影響；
錨點在影片開始播放、暫停後續播、seek 時重新綁定到當下的播放器位置
（`data/ImuVideoTimeline.kt`，JVM 單元測試在 `app/src/test`）。

- BLE 積壓、每整分鐘的健康資料停頓、斷線重連造成的漏樣，會**如實變成時間軸上的缺口**，
  不會補成連續樣本——評分引擎需要知道那段沒有資料。
- 沒有裝置時鐘的裝置（非幀式協議品牌，`deviceTimestampUs == null`）退回舊的計數式時間軸：
  第一筆用錨點位置，之後每筆 +40 ms（25 Hz）。
- 2026-09-10 之前的錄製是舊的計數式時間軸（丟樣會累積成漂移、seek 會留下約 −480 ms 的時戳倒退），
  離線分析請依 Core `testdata/imu/README.md` 的說明先切連續段。

## Git

Remote：`git@github-ccy:CCY68/ActivityScoringPlayer.git`（帳號 `CCY68`，SSH 別名 `github-ccy`）。
`CLAUDE.md` 與 `.claude/` 不納入版控。
