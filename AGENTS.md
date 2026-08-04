# AGENTS.md — ActivityScoringPlayer

AI 代理在本 repo 作業的守則。工作區通用守則見上層 [`../AGENTS.md`](../AGENTS.md)；動手前先讀 [`DeviceModule-Internal.md`](./DeviceModule-Internal.md) 與 `../docs/B20*.pdf`。

輸出慣例：思考可用英文，結論與文件、commit 訊息一律使用台灣繁體中文。

## 本 repo 重點

- Android / Google TV（Kotlin）。含 Module A DeviceModule 與播放整合示範。
- DeviceModule 骨架：品牌適配器 + StateFlow 狀態機 + 指數退避重連。
- 一般品牌 `IBrandAdapter`（單 characteristic）；B20 `IFramedBrandAdapter`（幀式協議、拼包、跨包）。

## 不可違反

1. 健康與 IMU 走**獨立 listener**（App 訂 Health、Module B 訂 IMU）；健康數據直送 App，不經 Module B。
2. IMU 原始值**不在 DeviceModule 內濾波**（濾波屬 Module B）。
3. B20 裝置端時間戳為相對計數，**不可**當 epoch 用；改以接收時間 + 樣本序號 + 取樣率推算。
4. UUID 統一轉大寫比對；斷線必 `gatt.close()`（GATT 槽上限 7）；`uint8` 讀取記得 `and 0xFF`。
5. 新增品牌依 `DeviceModule-Internal.md`「新增品牌的步驟」，勿破壞既有品牌行為。

## Git

- Remote 用別名：`git@github-ccy:CCY68/ActivityScoringPlayer.git`。
- `.claude/` 與 `CLAUDE.md` 不追蹤。僅在使用者要求時 commit / push。
