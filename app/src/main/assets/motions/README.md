# `.maf` 課程檔放置目錄

檔名沿用標註端（WebTool）交付的原始檔名 `<課程名>-<課程 id>.maf`，
由 `ScoringEngineFactory.MAF_FILE_BY_MOVIE_ID` 對照到 `MovieRepository` 的 `movieId`：

| `movieId` | 課程 | 檔名 |
|---|---|---|
| 0 | 銀髮族健康操 | `銀髮族健康操-17421781954041251.maf` |
| 1 | 初階瑜珈體位法 | `初階瑜珈-17421914658801191.maf` |
| 2 | 太極藝術體驗課 | `太極藝術體驗課-17428046223601321.maf` |
| 3、4 | 播放測試影片 | 無（播放頁降級為只播放、不評分） |

`ScoringEngineFactory.readMafBytes(movieId)` 找不到對應檔案時，`PlaybackViewModel` 會降級為
「只播放、不評分」模式（`isScoring = false`），不會拋錯中斷播放。

加密內容金鑰放在 `app/src/main/assets/keys/content-key.<key_id>.hex`，`key_id` 取自 MAF 信封內的
`payload.key_id`。格式與驗證規則詳見
[`ActivityScoringCore-Integration.md`](../../../../../ActivityScoringCore-Integration.md)。
