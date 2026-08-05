# `.maf` 課程檔放置目錄

依 `MovieRepository.kt` 目前的 `movieId`（0～4，對應 5 部示範影片），把課程檔命名為：

```
motions/0.maf
motions/1.maf
motions/2.maf
motions/3.maf
motions/4.maf
```

`ScoringEngineFactory.readMafBytes(movieId)` 會讀取 `motions/$movieId.maf`；找不到對應檔案時 `PlaybackViewModel` 會降級為「只播放、不評分」模式（`isScoring = false`），不會拋錯中斷播放。

格式與驗證規則詳見 [`ScoringModule-Integration.md`](../../../../../ScoringModule-Integration.md)。

> 本檔案僅作為目錄佔位（git 不追蹤空目錄），放入實際 `.maf` 檔案後可保留或移除。
