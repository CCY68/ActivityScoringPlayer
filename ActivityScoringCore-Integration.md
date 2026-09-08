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
| 2026-09-09 | `4750b89`（`feat/participation-stats`，Core PR #6） | 393,915 bytes（sha256 前 16 碼 `25d5d21220852cc9`） | D2 活動參與指標：新增 `ScoringEngine.participation: StateFlow<ParticipationSnapshot>`；**`stop()` 介面變更**為 `suspend fun stop(videoTimeMs: Long? = null): ParticipationSnapshot`（原始碼相容，舊呼叫 `engine.stop()` 照舊可用）。三面向與心率行為不變 |

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

## 2. App 目前怎麼用它（實際呼叫路徑）

App **不會直接**呼叫 `MafLoader`，是透過 `activity-scoring-core.aar` 提供的 `ScoringEngine.loadMaf(...)` 間接使用：

```
PlaybackViewModel (app)
  → ScoringEngineFactory.loadMaf(engine, movieId)
      ├─ 讀 assets: motions/$movieId.maf
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

固定路徑：`app/src/main/assets/motions/<movieId>.maf`（`movieId` 對應 `MovieRepository` 裡的影片清單，例如 `0.maf`）。

加密內容金鑰固定放在 `app/src/main/assets/keys/content-key.<key_id>.hex`。例如 MAF 內的
`payload.key_id` 是 `aswt-maf-2026-08-k1`，檔名就必須是
`content-key.aswt-maf-2026-08-k1.hex`。金鑰內容為 64 個十六進位字元（32 bytes）。

## 3. 如果你手上有一份 `.maf` 檔，怎麼用

### 方法一：接進這個 App（唯一目前支援的路徑）

1. 把檔案改名成 `<movieId>.maf`，放到 `app/src/main/assets/motions/<movieId>.maf`。
2. 依 `payload.key_id` 命名內容金鑰，放到 `app/src/main/assets/keys/content-key.<key_id>.hex`。
3. 確認 `movieId` 跟 `MovieRepository.kt` 裡影片清單對得上。
4. Rebuild 裝進 APK。`PlaybackViewModel` 會自動嘗試載入，`state.isScoring == true` 代表載入成功進入評分模式。

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
