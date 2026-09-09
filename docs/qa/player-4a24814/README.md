# Player 模擬器 QA 紀錄（2026-09-09）

## 結論與範圍

基準 `4a24814144ff4e9ab4bf8dda45f26c7939f1cbc8` 通過本次「可建置、安裝、啟動、短程串流播放、手動結束並返回首頁」基本流程驗證。這不是完整課程、實體 TV／B20 或評分準確度驗收。未修改產品程式碼，僅提交 QA 文件與畫面證據。

QA 分支：`qa/player-emulator-4a24814`。獨立工作目錄：`/mnt/e/codeRepo/worktrees/ActivityScoringPlayer-qa-4a24814`；本機 `/home/wendell/codeRepo/worktrees/ActivityScoringPlayer-qa-4a24814` 實際指向此目錄，不是 ext4 上的獨立副本。

## 環境與建置

- WSL JDK：17.0.20.1+1；Android SDK：`/home/wendell/Android/Sdk`。
- Windows Android Emulator：37.1.11.0；WHPX 加速可用。
- 專用 AVD：`Player_QA_TV34`，Android TV API 34 x86，1280×720，2 GB RAM；裝置序號 `emulator-5554`。
- Windows ADB 安裝 WSL 建置的 APK；應用程式 `com.johnson.fitness`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，約 24 MB，minSdk 23、targetSdk 36。
- APK SHA-256：`07f64ad6bddb746ec5b91129da608d655c14d08a6c2a21514674a2015a90ccea`。

執行：

```sh
export JAVA_HOME=/home/wendell/.local/jdks/jdk-17.0.20.1+1
export ANDROID_HOME=/home/wendell/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
bash ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

結果：`BUILD SUCCESSFUL in 1m 58s`，52 個工作；Lint `0 errors, 53 warnings, 1 hint`。`testDebugUnitTest` 為 **NO-SOURCE**，不能解讀為有單元測試通過。APK 簽章檢查退出碼 0。Lint 本機報告位於 `app/build/reports/lint-results-debug.html` 與 `.txt`，未提交建置產物。

## 實測結果

| 測項 | 結果與證據 |
|---|---|
| 安裝 | `adb install -r` 回覆 `Success` |
| 冷啟動 | `am start -W -n com.johnson.fitness/.MainActivity`：`Status: ok`、`LaunchState: COLD`、`TotalTime: 2783` ms |
| 首頁及遙控導航 | 首頁正常呈現；方向鍵與確認鍵可進入銀髮族健康操詳情；[首頁](01-home.png) |
| 播放模式選擇 | 選擇正常模式（B20）及不收錄 CSV，可進入播放頁；此段使用模擬器座標點擊，未證明全流程僅用遙控器可完成；[播放準備](02-playback.png) |
| 串流播放 | 點擊播放後有實際影片影像；兩次截圖人物動作與進度條皆改變，非僅啟動播放器；[播放與未連線提示](03-running.png)、[後續播放](04-progress.png) |
| 未連手環 | 顯示未連線提示，確認後仍可觀看；IMU 0、Core 尚未啟動，心率為缺值；不宣稱 Core 已執行評分 |
| 手動結束評分 | 成果卡顯示「無有效評分」、運動時長 `00:52`；熱量、平均心率、平均體溫皆為「－」，未生成假數值；[成果卡](05-result.png) |
| 返回首頁 | 成果卡後以兩次 Back 返回首頁，UI hierarchy 包含課程清單，應用程式仍在前景 |
| 閃退檢查 | 播放後及返回首頁後 `adb logcat -d -b crash` 均無輸出；僅代表本次流程未記錄 crash，不代表長時間穩定性驗收 |

首次黑畫面經 `dumpsys power` 確認為模擬器休眠（`mWakefulness=Asleep`），喚醒並延長專用 AVD 螢幕逾時後正常，未改 App 排除此問題。

## 待處理的顯示問題（未阻擋播放）

1. 未接手環、Core 尚未啟動時，HUD 仍顯示總分 `0` 與動作準度 `0%`，容易被理解成已量測的低分；建議此狀態也以「未量測／－」呈現。成果卡已有正確顯示「無有效評分」。
2. 「尚未連接手環」提示使用「健康警告」標題；建議改用裝置連線提示，避免讓使用者誤認為生理異常。
3. 播放頁「目前動作」仍有範例拉丁文案；展示前宜改成實際文字或隱藏。

另觀察到結束評分後影片仍繼續播放，成果卡時長固定為停止評分當下的 00:52，而背景進度稍後為 01:00。是否需要同時停止影片屬產品行為待確認，本次未視為演算法錯誤。

## 未涵蓋與重現方式

未測：B20 配對／重連／IMU 與健康數據、Replay CSV、完整 19:47 課程自然結束、100 次穩定性、其他課程、其他 Android API／ABI、全流程純遙控器操作、音訊（AVD 以 `-no-audio` 啟動）。依目前產品範圍不驗收暫停、倒帶及重播。

重現基本流程：建置上述 APK → 在 Android TV API 34 AVD 安裝 → 啟動 `com.johnson.fitness/.MainActivity` → 銀髮族健康操 → 正常模式（B20）→ 不收錄 → 播放 → 確認未連線提示 → 等待約一分鐘 → 結束評分 → 返回首頁。需要串流網路可用。

本機保留專用 AVD 與 SDK 映像供重測；不提交 `qa-avd/`、APK、SDK、原始執行紀錄及無效的休眠黑畫面。此分支只有 QA 交付，不合併主幹。
