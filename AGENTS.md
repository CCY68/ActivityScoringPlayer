# AGENTS.md — ActivityScoringPlayer（Scoring Demo Player）

AI 代理在本 repo 作業的守則。工作區通用守則見上層 [`../AGENTS.md`](../AGENTS.md)；
動手前先讀 [`ActivityScoringCore-Integration.md`](./ActivityScoringCore-Integration.md)。

輸出慣例：思考可用英文，結論與文件、commit 訊息一律使用台灣繁體中文。

## 本 repo 是什麼

- Android / Google TV（Kotlin、Compose for TV）的**示範 App**。
- 組成＝ **2 個 AAR ＋ 使用範例**：`activity-scoring-core.aar`（Module B，來源 `ActivityScoringCore`）、
  `device-module.aar`（Module A，來源 `WtivityDeviceModule`），本 repo 只寫接線與畫面。
- 沒有後端；課程清單寫死在 `data/MovieRepository.kt`，`.maf` 與金鑰在 `app/src/main/assets/`。

## 不可違反

1. **不要在本 repo 改評分演算法或 BLE 實作**：那屬於來源 repo。這裡只能改接線、畫面與文件。
2. 換 AAR 一定要同步更新 `ActivityScoringCore-Integration.md` 的版本表
   （日期／來源 commit／檔案大小／sha256 前 16 碼），並在 commit 訊息寫清楚。
3. 健康與 IMU 走**獨立 listener**；健康數據直送 App，不經評分引擎。心率不進入動作分數。
4. 送進 `ScoringEngine.submitImuSample()` 的時戳一律是**影片時間**；丟樣以缺口進 Core，**不補樣本**。
5. `.maf` 檔名與 `movieId` 的對照在 `ScoringEngineFactory.MAF_FILE_BY_MOVIE_ID`，
   課程顯示設定在 `CourseDisplaySettings`；新增課程三處都要補。
6. 對外文案限「心率與運動強度監看」，不得出現醫療／診斷／急救字樣。
7. 太極課程的 HUD／成果卡顯示規則（`CourseDisplaySettings`）尚未定案，不要自行調整。

## Git

- Remote 用別名：`git@github-ccy:CCY68/ActivityScoringPlayer.git`。
- `.claude/` 與 `CLAUDE.md` 不追蹤。僅在使用者要求時 commit / push。
