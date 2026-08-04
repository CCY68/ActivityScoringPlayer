# ActivityScoringPlayer

Google TV 播放示範 App，內含 **Module A · 手環連線管理（DeviceModule）**。串接健身手環、播放課程影片，並示範對接評分引擎（Module B）的整合流程。

- **平台**：Android / Google TV（Kotlin）
- **主硬體**：DoctorOne B20（BLE，單腕 IMU + PPG）
- **DeviceModule**：BLE 連線、品牌適配、幀式協議、自動重連

## 文件

- [`DeviceModule-Internal.md`](./DeviceModule-Internal.md) — DeviceModule 內部實作原理
- [`PROJECT.md`](./PROJECT.md) — 本元件定位與注意事項
- [`AGENTS.md`](./AGENTS.md) — AI 協作守則
- [`../PROJECT.md`](../PROJECT.md) — 工作區全貌與跨元件契約

## 資料流

```
手環(B20, BLE) → DeviceModule
   ├─ 健康數據（HR/HRV/SpO2/體溫）── 直送 ──▶ App 顯示
   └─ IMU DeviceFrame ─────────────────────▶ Module B 評分
```

DeviceModule 以「品牌適配器 + StateFlow 狀態機 + 指數退避重連」為骨架：一般品牌走 `IBrandAdapter`，B20 走 `IFramedBrandAdapter`（幀式請求/響應協議）。

## Git

Remote：`git@github-ccy:CCY68/ActivityScoringPlayer.git`（帳號 `CCY68`，SSH 別名 `github-ccy`）。
`CLAUDE.md` 與 `.claude/` 不納入版控。
