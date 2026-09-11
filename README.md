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
| 課程目錄（60 支課程） | 甲方課程清單 | `app/src/main/assets/courses.json` |
| 範例程式（播放、接線、HUD、成果卡、CSV 錄製與回放） | 本 repo | `app/src/main/java/com/johnson/fitness/` |

- **平台**：Android / Google TV（Kotlin、Compose for TV）
- **主硬體**：DoctorOne B20（BLE，單腕 IMU + PPG）
- **沒有會員後端**：課程目錄是打包在 App 裡的 `assets/courses.json`（見下節），`.maf` 打包在 assets；
  唯一會呼叫的 API 是 Welltivity 的**免驗證**課程資訊端點，用來查影片的播放網址。

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
| 首頁 | 影片牆：依課程分類分列（每列橫向捲動、D-pad 導覽、焦點放大），卡片顯示標題、時長與「可評分／僅播放」 |
| 詳情頁 | 課程說明、是否有 `.maf`、「開始課程」→ 選擇播放模式（正常 B20／Replay CSV）與是否收錄 CSV |
| 播放頁 | 影片 + 即時評分 HUD（三面向）＋ 心率區間；結束顯示成果卡 |
| 錄製資料 | 首頁側欄「數據」。列出本機收錄的 IMU CSV，可直接回放、可刪除（見下「錄製資料頁」一節） |
| 設定 | 藍牙配對、使用者資料（生理參數，見下） |
| 藍牙 | 掃描、連線、記住上次裝置 |

## 課程目錄（`courses.json`）

首頁的影片牆直接讀 `app/src/main/assets/courses.json`。**這份檔案是給非工程師維護的**：
加一支課程＝在 `courses` 陣列裡多加一個物件，存檔後重新安裝 App 即可，不必改任何程式碼。

```json
{
  "version": 1,
  "courses": [
    {
      "courseId": "17421781954041251",
      "title": "銀髮族健康操",
      "category": "伸展與活動度",
      "durationSec": 1200,
      "thumbnailUrl": "",
      "mafAsset": "銀髮族健康操-17421781954041251.maf"
    }
  ]
}
```

| 欄位 | 必填 | 說明 |
|---|---|---|
| `courseId` | ✅ | 平台的課程編號（純數字字串）。**App 內部就用它當課程識別碼**，也用來查播放網址與配對 `.maf`。 |
| `title` | ✅ | 卡片與詳情頁顯示的課程名稱。 |
| `category` | | 首頁分列用的分類名稱；留空會歸到「其他課程」。同名的課程會排在同一列。 |
| `durationSec` | | 課程長度（秒）。留空或 0 時卡片不顯示時長。 |
| `thumbnailUrl` | | 卡片縮圖網址。**目前 60 支都留空**，留空時卡片用依課程編號決定的純色底＋文字。 |
| `mafAsset` | | `app/src/main/assets/motions/` 底下的 `.maf` 檔名。留空時 App 會自動找檔名以 `-<courseId>.maf` 結尾的檔案；找不到＝這支課程「僅播放、不評分」。**填了檔名卻沒放檔案**時播放頁會顯示「評分資料載入失敗」（不會偷偷降級成純播放），以免缺檔沒人發現。 |

**播放網址不寫在目錄裡。** 網址會隨 CDN 重新編碼而改變，所以每次要播才向免驗證端點查：

```
GET https://asia.welltivity.com.tw/api/app/open/course/info?courseId=<courseId>
→ {"code":200,"data":{"playUrl":"https://….m3u8","courseTitle":"…"}}
```

（`data/CoursePlayUrlRepository.kt`；同一次執行中查過會快取。查不到時播放頁會顯示「取得播放網址失敗」與重試，
不會停在黑畫面。串流本身播不起來（HLS 404／CDN 故障／播到一半斷網）時，畫面會疊出「播放失敗」對話框，
重試會**略過快取重查網址**並從失敗當下的位置接回去，不重建播放器、不重設評分引擎。）

### 加一支已標註課程（可評分）

1. 把標註端交付的 `<課程名>-<課程編號>.maf` 放進 `app/src/main/assets/motions/`。
2. 確認 `courses.json` 裡有同一個 `courseId` 的課程；`mafAsset` 可以填檔名，也可以留空讓 App 自己配對。
3. 內容金鑰（`assets/keys/content-key.<key_id>.hex`）要涵蓋這支 `.maf` 的 `key_id`。
4. 重新安裝 App。首頁最上面那列「可評分課程」會出現這支課程，卡片標記變成「可評分」。

`./gradlew :app:testDebugUnitTest` 會檢查目錄格式、`courseId` 不重複，以及
「`courses.json` 寫到的 `.maf` 都存在」「`assets/motions/` 的每支 `.maf` 都有課程對得上」
（`CoursesAssetTest`），格式打錯不用裝機就知道。

### 目錄壞掉會怎樣

`courses.json` 讀不到或格式錯誤時，首頁顯示**「課程目錄載入失敗」錯誤畫面**（含錯在第幾筆的說明與「重試」），
**不會**變成空白首頁。

### 使用者資料（生理參數）

`設定 → 使用者資料` 可調年齡、靜息心率、生理性別、體重、身高，存在 SharedPreferences
（`data/UserProfilePreferences.kt`），下一次進播放頁建立引擎時套用。
沒調整過時使用 **Demo 預設值**（30 歲／靜息 65 bpm／男性／70 kg／170 cm），畫面上會明白標示；
這組值不是任何真實受測者的資料。不提供 β 阻斷劑與處方心率上下限欄位（屬醫療用途，不在本 Demo 範圍）。

## 錄製資料頁

首頁側欄「數據」進去是**錄製資料頁**（`ui/recordings/`），列出本機收錄的 IMU CSV
（`data/ImuCsvStore.listRecordings()`），電視遙控器可直接操作，不用再透過系統檔案選擇器找檔案。

- 每列顯示檔名、對到的課程名稱（由檔名前段的課程編號比對 `courses.json`；舊檔名或對不到課程時
  顯示「未知課程」）、錄製時間、檔案大小，以及筆數／時長（背景讀檔計算，算好前顯示「計算中…」）。
- **回放**：檔名帶課程編號且對得到課程，直接進播放頁 Replay CSV 模式；舊檔名或對不到課程時，
  先跳出「選擇課程」對話框（只列有 `.maf` 的課程），選完才進播放頁。
- **刪除**：二次確認後刪除，Android 10 以上連同 MediaStore 索引一起清掉。
- 空清單時顯示提示：到課程詳情頁選「正常模式（B20）→ 收錄 CSV」開始錄製。

這頁只負責列舉現有檔案，**不會**修改「全程靜坐錄製流程」（下一節）本身的錄製步驟。

### 上傳到 Google Drive

電視上沒有 Google 登入，Drive API 沒辦法匿名寫入，所以上傳走**使用者自己部署的
Google Apps Script Web App** 當中繼（`data/RecordingUploader.kt`）。網址與 token
不寫進 repo，只存在 App 的 SharedPreferences（`data/UploadPreferences.kt`），設定頁
「設定 → 錄製上傳」可以編輯／清空，但電視遙控器打字很痛苦，**開發期主要靠 adb 灌值**：

```
adb shell am start -n com.johnson.fitness/.MainActivity --es upload_url "<Apps Script /exec 網址>" --es upload_token "<token>"
```

只有 **debug build** 接這兩個 intent extra（`MainActivity.applyDebugUploadConfigIfPresent`）；
release 版不接，避免上傳目的地被任何外部 intent 竄改。指令可以只帶其中一個 extra
（例如只想換 token）；成功會跳 Toast「已更新上傳設定」。

錄製資料頁每列在「已設定」時會多一顆「上傳」（已上傳過則是「重新上傳」）按鈕，
頂部有「全部上傳」只處理尚未上傳成功的檔案、逐一序列進行並顯示「3/7 上傳中…」；
已上傳過的檔案重開 App 仍顯示「✓ 已上傳」（記在 `UploadPreferences`）。
未設定網址／token 時，整頁隱藏所有上傳相關按鈕，副標改顯示「未設定上傳（設定 → 錄製上傳）」。

**中繼契約**（Apps Script 端已部署好，App 端不用管實作）：

```
POST <uploadUrl>
Content-Type: application/json

{ "token": "<token>", "fileName": "17421781954041251_2026-09-11_22-33-55-680.csv",
  "subfolder": "17421781954041251", "contentBase64": "<CSV 內容的 base64>" }
```

`subfolder` 用課程編號（`Recording.courseId`）分資料夾，拿不到就不帶這個欄位。
回應一律 HTTP 200，要看 body 的 `ok`：成功
`{"ok":true,"id":"…","url":"https://drive.google.com/…","name":"…"}`，
失敗 `{"ok":false,"error":"…"}`。實測還發現中繼偶爾會把這次 POST 誤答成 doGet
的健康檢查（`ok=true` 但沒有 `id`）——這種情況 App 端會自動重試一次，重試後還是
這樣才當失敗回報。`/exec` 對 POST 會回 302 轉到 `script.googleusercontent.com`
（該端點只接受 GET），OkHttp 預設 `followRedirects(true)` 會自動處理，不用自己實作轉址。

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

- 檔名格式 `<courseId>_<yyyy-MM-dd_HH-mm-ss-SSS>.csv`（例：`17421781954041251_2026-09-11_22-33-55-680.csv`），
  課程編號拿不到時用 `unknown` 代替。App 內建的「錄製資料頁」（首頁側欄「數據」）可以直接看到
  每一支錄製對到哪堂課、錄了多久，不用自己對檔名；這裡的手動取檔流程是給要把素材搬去 Core
  `testdata/imu/` 的情境用的。
- Android 10 以上：`Download/ActivityScoringPlayer/<檔名>`；更舊的版本落在 App 專屬的
  Documents 目錄（`getExternalFilesDir(Documents)`）。
- 取回：Android 10 以上 `adb pull /sdcard/Download/ActivityScoringPlayer/`；
  Android 9 以下（例如 Android 8 的電視盒）`adb pull /sdcard/Android/data/com.johnson.fitness/files/Documents/`。
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
