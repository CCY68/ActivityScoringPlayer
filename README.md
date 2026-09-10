# ActivityScoringPlayer — Scoring Demo Player

Google TV 示範 App：把 **`activity-scoring-core.aar`（Module B 評分引擎）** 與
**`device-module.aar`（Module A 手環連線）** 兩個函式庫接起來，播課程影片、收手環 IMU 與心率，
即時顯示評分 HUD 與課後成果卡。

**這個 repo ＝ 2 個 AAR ＋ 使用範例**，本身不含評分演算法，也不含 BLE 實作：

| 內容 | 來源 repo | 放在哪 |
|---|---|---|
| `activity-scoring-core.aar`（含 MAF 解析／解密，不再需要獨立 `maf-format.jar`） | `ActivityScoringCore` | `app/libs/` |
| `device-module.aar`（Module A DeviceModule） | `WtivityDeviceModule` | `app/libs/` |
| `.maf` 課程檔與內容金鑰 | `ActivityScoringWebTool` 標註端 | `app/src/main/assets/motions/`、`assets/keys/` |
| 範例程式（播放、接線、HUD、成果卡、CSV 錄製與回放） | 本 repo | `app/src/main/java/com/johnson/fitness/` |

- **平台**：Android / Google TV（Kotlin、Compose for TV）
- **主硬體**：DoctorOne B20（BLE，單腕 IMU + PPG）
- **沒有後端**：課程清單寫死在 `data/MovieRepository.kt`，`.maf` 打包在 assets，
  影片由 ExoPlayer 直接取串流位址播放；App **不呼叫任何 API**。

## 文件

- [`ActivityScoringCore-Integration.md`](./ActivityScoringCore-Integration.md) — Core AAR 的接線方式、`.maf` 課程檔怎麼放、AAR 版本表
- [`DeviceModule-Internal.md`](./DeviceModule-Internal.md) — **歷史文件**：DeviceModule 內部實作原理。
  DeviceModule 已獨立成 `WtivityDeviceModule` repo，該 repo 的文件才是唯一來源，本檔僅供舊紀錄查閱
- [`PROJECT.md`](./PROJECT.md) — 本元件定位與注意事項
- [`AGENTS.md`](./AGENTS.md) — AI 協作守則
- [`../PROJECT.md`](../PROJECT.md) — 工作區全貌與跨元件契約

## 資料流

```
手環(B20, BLE) → device-module.aar
   ├─ 健康數據（HR/HRV/SpO2/體溫）── 直送 ──▶ App 顯示
   └─ IMU 樣本（換算成影片時間軸）───────────▶ activity-scoring-core.aar 評分
                                                  └─▶ App HUD／成果卡
```

心率**不進入動作分數**，只用於強度區間與熱量估算（`設定 → 使用者資料` 的年齡／靜息心率／體重／身高）。
對外聲明限「心率與運動強度監看」，不涉及醫療、診斷或急救。

## 畫面

| 畫面 | 說明 |
|---|---|
| 首頁 | 依課程分類分列的示範課程（3 堂已標註課程 + 2 支播放測試影片） |
| 詳情頁 | 課程說明、是否有 `.maf`、「開始課程」→ 選擇播放模式（正常 B20／Replay CSV）與是否收錄 CSV |
| 播放頁 | 影片 + 即時評分 HUD（三面向）＋ 心率區間；結束顯示成果卡 |
| 設定 | 藍牙配對、使用者資料（生理參數，見下） |
| 藍牙 | 掃描、連線、記住上次裝置 |

### 使用者資料（生理參數）

`設定 → 使用者資料` 可調年齡、靜息心率、生理性別、體重、身高，存在 SharedPreferences
（`data/UserProfilePreferences.kt`），下一次進播放頁建立引擎時套用。
沒調整過時使用 **Demo 預設值**（30 歲／靜息 65 bpm／男性／70 kg／170 cm），畫面上會明白標示；
這組值不是任何真實受測者的資料。不提供 β 阻斷劑與處方心率上下限欄位（屬醫療用途，不在本 Demo 範圍）。

## 全程靜坐錄製流程

Core 的評分回歸測試需要一份**真的坐著不動**的 IMU 錄製當零分錨（Core `docs/評分修復更新計畫_v1_20260907.md`
決策 B3；素材放在 Core `testdata/imu/`）。名義上「戴著錶看影片」的錄製實測有三成時間在動，不能用，
因此錄製時請照下列流程做。

**前置**

1. B20 充飽電、戴在慣用手腕上（鬆緊以不滑動為準），開機。
2. App 先到藍牙設定頁完成連線一次（之後會自動連回上次的裝置）。
3. 找一張有靠背的椅子；錄製全程手腕放在大腿或扶手上不動。

**錄製**

1. 首頁選課程 → 詳情頁按 **開始課程** →「選擇播放模式」選 **正常模式（B20）**。
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
兩個 AAR 由來源 repo 建置後手動複製過來，不會自動同步（步驟見 `ActivityScoringCore-Integration.md`）。
`CLAUDE.md` 與 `.claude/` 不納入版控。
