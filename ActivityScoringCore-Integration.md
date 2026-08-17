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
