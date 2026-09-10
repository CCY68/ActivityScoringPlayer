# `.maf` 課程檔放置目錄

檔名沿用標註端（WebTool）交付的原始檔名 `<課程名>-<課程 id>.maf`。**沒有寫死的對照表**：
課程目錄 `app/src/main/assets/courses.json` 的 `mafAsset` 指定檔名，留空時由
`CourseCatalog.resolveMafAsset()` 自動找檔名以 `-<courseId>.maf` 結尾的檔案。

| 課程編號（`courseId`） | 課程 | 檔名 |
|---|---|---|
| 17421781954041251 | 銀髮族健康操 | `銀髮族健康操-17421781954041251.maf` |
| 17421914658801191 | 初階瑜珈體位法 | `初階瑜珈-17421914658801191.maf` |
| 17428046223601321 | 太極藝術體驗課 | `太極藝術體驗課-17428046223601321.maf` |

目錄裡其餘課程沒有 `.maf`，播放頁會降級為「只播放、不評分」。
`ScoringEngineFactory.readMafBytes(movieId)`（`movieId` 就是課程編號）找不到對應檔案時，
`PlaybackViewModel` 會降級為「只播放、不評分」模式（`isScoring = false`），不會拋錯中斷播放。

新增一支已標註課程只要「放檔案 ＋ 目錄裡有同一個 `courseId` 的課程」，不必改任何 Kotlin 程式碼；
`./gradlew :app:testDebugUnitTest` 的 `CoursesAssetTest` 會檢查兩邊對得上。

加密內容金鑰放在 `app/src/main/assets/keys/content-key.<key_id>.hex`，`key_id` 取自 MAF 信封內的
`payload.key_id`。格式與驗證規則詳見
[`ActivityScoringCore-Integration.md`](../../../../../ActivityScoringCore-Integration.md)。
