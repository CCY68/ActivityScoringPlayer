# Module B 整合筆記：Core AAR 與 `.maf` 課程檔

本文件說明 Core AAR 內建的 MAF 解析能力、App 目前怎麼使用，以及如果你手上有一份 `.maf` 檔要怎麼載入測試。

> 完整的 MAF JSON schema 定義、加密/簽章規劃（ADR 0019）等規格文件，在另一個 repo `ActivityScoringCore` 的 `ActivityScoringCore-SDK.md`，本文件只記錄「這個 Player repo 怎麼用它」。

## 1. MAF 解析器如何交付

- 原始碼仍位於 `ActivityScoringCore/maf-format`（package `com.motionmaf.format`），但建置時會直接編入 `activity-scoring-core.aar`，Player 不再需要獨立 `maf-format.jar`。
- 用途：解析、驗證 **MAF（Motion Assessment Format）** 格式的 `.maf` 課程檔——動作評分課程用的參考資料（分段時間軸、軌跡參考、節奏參考、片段相似度參考等）。
- `kotlinx-serialization-json` 仍是外部 runtime dependency，Player 的 `app/build.gradle.kts` 必須保留該依賴。

### 更新這個 jar

`ActivityScoringCore` 那邊原始碼有改動時，要重新 build 並手動複製過來，兩邊不會自動同步：

```bash
cd ../ActivityScoringCore
./gradlew :activity-scoring-core:assembleRelease
cp activity-scoring-core/build/outputs/aar/activity-scoring-core-release.aar \
  ../ActivityScoringPlayer/app/libs/activity-scoring-core.aar
```

### 目前內建的 AAR 版本

| 日期 | Core commit | 大小 | 備註 |
|---|---|---|---|
| 2026-09-09 | `ae7fdaf`（main） | 368,308 bytes | 評分修復 PR-C1/C1b/C2：靜止＝0 分、互補濾波重力追蹤、形狀通道循環鎖定；`Score.confidence` 語意見下 |
| 2026-09-09 | `8da87a2`（main） | 367,588 bytes | PR-C1c：節奏不容忍 2× 整流歧義（A6）、刪除 PLV 快分量（B4）；行為對 Player 無介面變更 |
| 2026-09-10 | `7a0f399`（main） | 410,361 bytes（sha256 前 16 碼 `a8994d7ffd5bb62d`） | D2 活動參與指標：新增 `ScoringEngine.participation: StateFlow<ParticipationSnapshot>`；**`stop()` 介面變更**為 `suspend fun stop(videoTimeMs: Long? = null): ParticipationSnapshot`（原始碼相容，舊呼叫 `engine.stop()` 照舊可用）。含 D2.1（`regularityMinWindows = 3`、`regularityMinPeriodicity = 0.50`、`regularityMinActiveRatio = 0.75`）與 D2.2（`countOutsideSegments = true`，整堂課統計）。三面向與心率行為不變 |
| 2026-09-11 | `aa93e93`（main） | 421,770 bytes | PR-C4 契約收斂（族 B 前臂方向接線、共用向量）、PR-C3 中心值混合路徑（`descriptors[].value` 有值時才生效，PROVISIONAL）、離線估計器細化；`DescriptorFrame` 多三個尾端欄位，Player 需重新編譯 |
| 2026-09-11 | `2fe2cf7`（main） | 448,345 bytes | D1 前臂仰角通道（`ScoringConfig.elevationChannelEnabled` 預設 false，關閉時逐位元不變）、MAF `forearm_elevation_reference` 解析、靜止窗 planarity NaN 修正；Core 281/281 |
| 2026-09-11 | `eba211b`（main） | 448,348 bytes（sha256 前 16 碼 `428c9ca14f219476`） | 以最新 main 重建；程式碼行為沿用 `2fe2cf7` 的 D1 仰角通道，後續 commit 為批次 B／C 成果與契約文件更新 |

### 目前內建的 device-module.aar 版本

| 日期 | DeviceModule commit | 大小 | 備註 |
|---|---|---|---|
| 2026-09-10 | `0bf8604`（main） | 138,447 bytes | B20 時戳改以裝置時鐘為主（`ImuData.deviceTimestampUs` 新增）、104→25 Hz 改格點重取樣、重連沿用時間軸、`setImuSampleRate()` 重連後自動重套 |
| 2026-09-11 | `dev_wdl`（fix/late-frame-reorder） | 161,181 bytes | 亂序遲到幀整幀丟棄，不再誤判為感測器重開（實錄兩筆同毫秒樣本的來源）；含停頓診斷 API |
| 2026-09-11 | `ceaef83`（main） | 161,185 bytes（sha256 前 16 碼 `e58cd559d712b24e`） | PR #3 合入 main：B20 亂序遲到幀整幀丟棄、不重錨也不誤判感測器重開；含遲到幀統計與停頓診斷 API |

### 送進引擎的時間軸（P5）

`ScoringEngine.submitImuSample()` 收到的 `RawImuSample.timestampMs` 一律是**影片時間**（ms），
由裝置端時鐘換算（`data/ImuVideoTimeline.kt`）：

```
videoTimeMs = (deviceTimestampUs − 錨點裝置時戳) / 1000 + 錨點影片位置
```

- 錨點在影片開始播放、暫停後續播、`seek` 時重新綁定；`engine.start()`／`resume()`／`seek()`
  的影片位置與錨點位置一致。
- 丟樣（BLE 積壓、每整分鐘健康資料停頓、斷線重連）**以缺口進 Core，不補樣本**；
  Core 端的視窗填充率／缺口規則據此判斷缺測。
- 裝置時鐘倒退（感測器重開歸零）以「上一筆 + 40 ms」續接，保證送進 Core 的時戳單調。
- `deviceTimestampUs == null`（非幀式協議品牌）退回計數式時間軸：第一筆用錨點位置，之後每筆 +40 ms。
- 錨點綁在**第一筆樣本到達當下**的影片位置，並扣掉該筆的送達延遲
  （`System.currentTimeMillis() − ImuData.timestampMs`，上限 2 s）：重錨到第一筆樣本之間的等待
  會讓時間軸整段落後，用 BLE 積壓的樣本錨定則會讓整段超前，兩者都要修掉。
  送達延遲另外扣掉「手機 wall clock 校時量」——DeviceModule 的 epoch 基準要變化超過 30 s 才會
  重新錨定，小幅校時會讓 `timestampMs` 永久偏掉，故以「wall clock − `elapsedRealtime`」的跳動量累積補償。
- 開播／暫停／續播的重錨**保留單調下限**（上一筆輸出 + 40 ms）：續播後最先到達的可能是暫停期間
  產生的積壓樣本，扣掉延遲後落在暫停前，這種樣本直接丟掉（成為缺口），不塞進續播段。
  往回 seek 才會清掉這個下限。
- seek 去抖動（120 ms）期間不送樣本，避免 Core 在 `engine.seek()` 生效前先收到重錨後的較早時戳。
- **所有 seek 都會走到重錨**：`PlaybackScreen` 除了自訂進度條，另外監聽播放器的
  `onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK)` 補送 `PlaybackIntent.Seek`，
  PlayerView 內建控制器與 D-pad 快轉／倒轉因此也會重錨並呼叫 `engine.seek()`。

**已知限制**：錄製中途往回 seek 的 CSV 無法完整重現當時的即時評分——CSV 只有單一 `timestamp_ms`
欄位，讀取時整份依它排序，同一段影片的兩遍樣本會交錯；且 seek 去抖動期間 LIVE 不送樣、錄製照寫，
兩邊接受的樣本集合本來就不同。靜坐錄製流程（README）因此要求全程不 seek。

錄製 CSV（`ImuCsvStore`）走同一套換算，因此 Replay CSV 模式回放時的時間軸與即時路徑一致；
舊 CSV（含 `video_position_ms` 欄位的版本）的讀取相容性不變。

### 顯示層對 `Score.confidence` 的處理（PR-P1，決策 A1／A3）

Core 1.1 起**靜止或訊號沒有週期結構時，不再回暖機分數，而是誠實回 `AVAILABLE` ＋ 低 `value` ＋ `confidence ≈ 0`**。
Player 顯示層（`PlaybackViewModel`）因此只採用 `availability == AVAILABLE && confidence >= 0.3` 的面向：

- 即時總分／各面向數字／課程最終平均都只納入合格面向；不合格面向顯示「－」。
- Core 有回分數但沒有任何合格面向 → HUD 顯示「等待動作」（`awaitingMotion`），不是 0 分。
- 整堂課都沒有合格分數 → 成果卡顯示「無有效評分」，不給 D 級。
- 「順序」（片段相似度）面向依決策 A3 延後，HUD 與成果卡皆不顯示；決策依據見 Core `docs/評分修復更新計畫_v1_20260907.md`。

### 活動參與統計（D2，計畫 §9）

Core 1.2 起多一個**不是分數**的輸出：`engine.participation: StateFlow<ParticipationSnapshot>`。
它回答「使用者**有沒有跟著動、我們量到多少**」，與三面向（動作是否精準）分開，不參與加權、不出總分。

| 欄位 | Player 用在哪 |
|---|---|
| `activeMs` | 成果卡「偵測到活動」、HUD「活動 N 分」 |
| `longestRunMs` | 成果卡「最長連續活動」（≤ 3 s 的短暫停頓不切斷） |
| `coverage`（`measuredMs / expectedMs`） | 成果卡「量測完整度」 |
| `rhythmRegularity: Float?` | 成果卡「節奏規律」；`null` 代表不可判斷，**UI 留白**（顯示「－」），不要顯示 0 |
| `settled` | `true` 才是結算完成的快照 |

呼叫端的三個重點：

1. **成果卡用 `engine.stop(videoTimeMs)` 的回傳值**，不要在 `stop()` 之後改讀 `participation.value`。
   `stop()` 現在是 `suspend fun stop(videoTimeMs: Long? = null): ParticipationSnapshot`，會等本次結算
   完成才返回。傳入結束當下的影片位置，Core 才能把「最後一筆 IMU 樣本到結束之間」的斷線算成未量測。
2. **進行中的即時快照**（1 Hz，event time）只給 HUD 用。
3. **課程層級的呈現由 Player 決定**：`CourseDisplaySettings.showActivityStats(movieId)` 為 false 的課程
   （目前只有太極）成果卡不顯示活動三項與三面向分數，只留參與時間、量測完整度與生理摘要。
   原因是慢動作在 Core 的靜止門檻 0.70 下多被判靜止——真實錄製的 `activeMs ÷ 評分段總長`只有 0.565
   （健康操是 0.87–0.98）。這一輪**不改 Core 常數、不動 MAF**（計畫 §9.2）。

「參與時間」（Player 既有的 `exerciseDurationMs`，實際跟著播放的時間）與「偵測到活動」（`activeMs`）
是**兩個不同的問題**，成果卡並列顯示，不要互相取代。

#### D2.1／D2.2（Core `7a0f399`）之後 UI 要注意的兩件事

1. **`activeMs` 是整堂課統計，不再以評分段閘門**（`countOutsideSegments = true`）：過場／暖身時
   在動的時間也算，因此**可能大於評分段總長**（Core 實測 1.03–1.19 倍）。
   UI **不可**用「`activeMs` ÷ 評分段總長」當百分比，也不要假設它小於課程長度的某個比例。
   唯一保證是 `activeMs ≤ measuredMs ≤ expectedMs`。目前成果卡顯示的是絕對分鐘數，符合這個約束。
2. **`rhythmRegularity` 會更常留白**：合格窗至少 3 個、ACF 峰值門檻 0.50、窗內活動比例門檻 0.75。
   Core 的驗收顯示兩份名義靜止錄製都退回 `null`，真正在跟做的五份仍有值（0.606–0.643）。
   `null` 一定要真的留白（顯示「－」），不可折成 0 或 0%。

### 心率管線需要的使用者生理資料

`ScoringEngine(config, heartRateProfile = …)` 的 `UserProfile` 由
`data/UserProfilePreferences.kt` 提供（設定頁「使用者資料」可調，存 SharedPreferences），
`ScoringEngineFactory.create()` 每次建引擎時重讀一次，所以改完設定下一堂課就生效。

- 未設定過時用 **Demo 預設值**（30 歲／靜息 65 bpm／男性／70 kg／170 cm，`CalorieModel.VO2R`），
  設定頁會標示「目前是 Demo 預設值」。PR-P4 之前這組值是寫死在 `ScoringEngineFactory` 裡的。
- `onBetaBlocker` 與處方心率上下限固定 false／null：屬醫療用途，本 Demo 不提供該欄位。
- 心率**不進入動作分數**，只影響強度區間與熱量估算。
- HUD 的心率區間直接用 Core 的 `HeartState.zone`（1..5＝白/藍/綠/黃/紅，分界為 %HRR 的
  20／40／60／80，`-1` 代表沒有有效心率），**顯示端不再自己算門檻**——自己算的話設定頁改了
  年齡／靜息心率也不會反映（PR-P4 之前 HUD 寫死 115／133／152／172 bpm，正好是 30 歲／靜息 65
  的 Karvonen 值）。沒有有效 bpm 的 tick 沿用上一筆的區間（PPG 約 1 Hz、整分鐘另有健康資料停頓，
  每個空 tick 都清會一直閃），但用 `eventTimeMs − featureTimeMs`（Core 誠實回報的資料年齡）
  超過 15 s 就把心率與區間清掉，避免手環斷線後 HUD 一直停在斷線前的讀值。
  **手環整支斷線時 Core 不會再送 `HeartState`**（事件時間靠 IMU 樣本推進），所以 Player 另外用
  `elapsedRealtime` 每秒檢查一次；只在影片播放中計時，暫停期間不算逾時、續播從當下重新起算。

## 2. App 目前怎麼用它（實際呼叫路徑）

App **不會直接**呼叫 `MafLoader`，是透過 `activity-scoring-core.aar` 提供的 `ScoringEngine.loadMaf(...)` 間接使用：

```
PlaybackViewModel (app)
  → ScoringEngineFactory.loadMaf(engine, movieId)
      ├─ 讀 assets: motions/<課程名>-<課程 id>.maf（由 courses.json 的 mafAsset／courseId 決定）
      └─ 依 payload.key_id 讀 assets: keys/content-key.<key_id>.hex
  → ScoringEngine.loadMaf(bytes, decryptor)             // AES-GCM JSON 信封解密
  → MafLoader.load(...)                                 // maf-format，本文件的主角
```

相關檔案：

- `app/src/main/java/com/johnson/fitness/ui/playback/PlaybackViewModel.kt` — 呼叫端，判斷 `state.isScoring`
- `app/src/main/java/com/johnson/fitness/data/ScoringEngineFactory.kt` — 讀 assets bytes
- `ActivityScoringCore/activity-scoring-core/src/main/java/com/fitness/activityscoringcore/reference/MafAssets.kt` — `Context.readMafAssetBytes(assetPath)`，唯一允許碰 `Context` 的地方
- `ActivityScoringCore/activity-scoring-core/src/main/java/com/fitness/activityscoringcore/engine/ScoringEngine.kt` — `loadMaf(...)`，內部即 `MafLoader(decryptor).load(...)`

### `.maf` 檔案放哪裡

固定目錄：`app/src/main/assets/motions/`。檔名沿用標註端交付的原始檔名
`<課程名>-<課程 id>.maf`。**沒有寫死的對照表**：課程目錄 `app/src/main/assets/courses.json` 的
`mafAsset` 指定檔名，留空時由 `CourseCatalog.resolveMafAsset()` 依 `courseId` 比對檔名尾碼。
Player 的 `movieId` **就是課程編號**（例如 `17421781954041251`），所以課程顯示設定
（`CourseDisplaySettings`）也直接用課程編號當 key。各檔名見 `app/src/main/assets/motions/README.md`。

加密內容金鑰固定放在 `app/src/main/assets/keys/content-key.<key_id>.hex`。例如 MAF 內的
`payload.key_id` 是 `aswt-maf-2026-08-k1`，檔名就必須是
`content-key.aswt-maf-2026-08-k1.hex`。金鑰內容為 64 個十六進位字元（32 bytes）。

## 3. 如果你手上有一份 `.maf` 檔，怎麼用

### 方法一：接進這個 App（唯一目前支援的路徑）

1. 把檔案放到 `app/src/main/assets/motions/`（檔名照標註端交付的原樣即可）。
2. 依 `payload.key_id` 命名內容金鑰，放到 `app/src/main/assets/keys/content-key.<key_id>.hex`。
3. 確認 `app/src/main/assets/courses.json` 裡有同一個 `courseId` 的課程；`mafAsset` 可填檔名，
   也可以留空讓 App 自己以 `-<courseId>.maf` 配對。**不需要改任何 Kotlin 程式碼。**
4. Rebuild 裝進 APK。`PlaybackViewModel` 會自動嘗試載入，`state.isScoring == true` 代表載入成功進入評分模式。

課程目錄裡配不到 `.maf` 的課程（`hasScoringData = false`）**不會**嘗試載入 MAF，
直接進 `PLAY_WITHOUT_SCORING`；「評分資料載入失敗」畫面只留給「預期有 `.maf` 卻載不起來」的情況。

目前**沒有匯入 UI**（無檔案選擇器、無 SAF/URI 讀取、無網路下載），只認 assets 裡的固定路徑。

### 方法二：不透過 App，直接用 `MafLoader` 驗證檔案格式

寫一段 Kotlin/JVM 程式（不需要 Android，純 JVM 即可跑），直接呼叫 `com.motionmaf.format.MafLoader`：

```kotlin
import com.motionmaf.format.MafLoader
import com.motionmaf.format.MafLoadResult
import com.motionmaf.format.ReviewPolicy
import com.motionmaf.format.AesGcmEnvelopeMafDecryptor
import java.io.File

fun main() {
    val bytes = File("你的檔案.maf").readBytes()
    val key = File("content-key.aswt-maf-2026-08-k1.hex")
        .readText().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val decryptor = AesGcmEnvelopeMafDecryptor { keyId ->
        if (keyId == "aswt-maf-2026-08-k1") key else null
    }

    val result = MafLoader(decryptor).load(
        rawBytes = bytes,
        expectedSha256 = null,                  // 有官方發佈的 SHA256 才需要帶
        reviewPolicy = ReviewPolicy.ALLOW_ANY    // 開發期先放寬；production 用 REQUIRE_REVIEWED_OK（預設值）
    )

    when (result) {
        is MafLoadResult.Success ->
            println("OK, timeline = ${result.timeline}")
        is MafLoadResult.SchemaVersionRejected ->
            println("schema_version 主版本不支援：找到 ${result.found}，支援 major=${result.supportedMajor}")
        is MafLoadResult.IntegrityFailure ->
            println("SHA256 不符：預期 ${result.expectedSha256}，實際 ${result.actualSha256}")
        is MafLoadResult.ParseError ->
            println("解析失敗：${result.message}")
        is MafLoadResult.SegmentValidationFailed ->
            result.errors.forEach { println("[${it.segmentId}] ${it.reason}") }
        is MafLoadResult.ReviewStatusRejected ->
            println("human_review_status 是 ${result.found}，要求 ${result.required}")
    }
}
```

### 常見卡關原因

1. **`schema_version` 主版本不符**：目前程式碼 `supportedSchemaMajor = 0`，檔案的 `schema_version` 開頭不是 `0.x` 就會被拒絕。
2. **`provenance.human_review_status` 不是 `REVIEWED_OK`**：預設 `ReviewPolicy.REQUIRE_REVIEWED_OK` 會擋下，開發階段測試自己的檔案要改傳 `ReviewPolicy.ALLOW_ANY`。
3. **結構驗證失敗**（`MafValidator`）：segment 時間邊界須滿足 `start_ms < scoreable_start_ms <= scoreable_end_ms < end_ms`；依 `kind`（`CYCLIC`/`HOLD`/`FLOW`/`TRANSITION`/`REST`）該有的 `trajectory_reference`/`tempo_reference` 要齊備；segment 之間不可重疊。
4. **金鑰或驗證標籤錯誤**：找不到 `content-key.<key_id>.hex`、金鑰不是 32 bytes，或 provenance／密文被修改，皆會 fail-closed，載入結果為 `ParseError`。
5. **HMAC**：Player 只持有內容金鑰，無法驗證 `provenance.hmac`；AES-GCM AAD 仍會把 provenance 與密文綁定，任一處被修改都無法解密。

## 4. 延伸閱讀

- `ActivityScoringCore/ActivityScoringCore-SDK.md` — 完整 MAF JSON schema、ProGuard 規則與單一 AAR 打包指令（`./gradlew :activity-scoring-core:assembleRelease`）
- `ActivityScoringCore/maf-format/src/test/kotlin/com/motionmaf/format/TestFixtures.kt` — 用程式碼建構的 MAF fixture 範例（沒有實體 `.maf` 檔可參考時，可以看這裡了解欄位長怎樣）
