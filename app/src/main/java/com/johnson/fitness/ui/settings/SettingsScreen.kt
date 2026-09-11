@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.johnson.fitness.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.fitness.activityscoringcore.heart.BiologicalSex
import com.fitness.activityscoringcore.heart.UserProfile
import com.johnson.fitness.BuildConfig
import com.johnson.fitness.FitnessApp
import com.johnson.fitness.data.UserProfilePreferences
import com.johnson.fitness.ui.common.isCompactWidth
import com.johnson.fitness.ui.common.touchClickable
import com.johnson.fitness.ui.theme.JohnsonColors

// 原本的「評分演算法」設定（SettlementAlgorithm）已隨 ActivityScoringCore 改版整個移除
// （新版三個評分面向各自獨立輸出，沒有可切換的結算演算法）。
// PR-P4 之後這裡多了「使用者資料」：Core 的心率區間與熱量估算需要年齡／靜息心率／體重／身高，
// 以前寫死在 ScoringEngineFactory，現在改成這頁可調（見 UserProfilePreferences）。
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onBluetoothClick: () -> Unit,
) {
    val horizontalPadding = if (isCompactWidth()) 20.dp else 56.dp
    val context = LocalContext.current
    val preferences = (context.applicationContext as FitnessApp).userProfilePreferences
    val uploadPreferences = (context.applicationContext as FitnessApp).uploadPreferences

    var profile by remember { mutableStateOf(preferences.load()) }
    var isCustomized by remember { mutableStateOf(preferences.isCustomized()) }
    // 每改一項就立刻寫回：這頁沒有「儲存」按鈕，離開畫面（甚至直接進播放頁）都要是已生效的值。
    val update: (UserProfile) -> Unit = { updated ->
        profile = updated
        preferences.save(updated)
        isCustomized = true
    }

    // 主要靠 debug 用的 adb intent 灌值（見 MainActivity），這裡的編輯對話框只是備用手段，
    // 但網址一樣要驗證（只接受 https）——漏打 https:// 會讓 OkHttp 在真的上傳時崩潰，
    // 存檔前擋掉、把原因顯示在對話框裡比事後崩潰好。
    // 直接觀察偏好的 StateFlow，不 remember 一份副本：停在這頁時用 adb 灌值也會即時反映，
    // 編輯對話框開啟時帶的也是最新值（否則按儲存會把剛灌進去的設定蓋回舊值）
    val uploadUrl by uploadPreferences.uploadUrl.collectAsStateWithLifecycle()
    val uploadToken by uploadPreferences.uploadToken.collectAsStateWithLifecycle()
    var editingUploadUrl by remember { mutableStateOf(false) }
    var editingUploadToken by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.BgApp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "APP 設定",
                        color = JohnsonColors.TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "設定",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = JohnsonColors.TextPrimary,
                        letterSpacing = (-0.3).sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onBack, modifier = Modifier.touchClickable(onClick = onBack)) { Text("返回") }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .height(1.dp)
                    .background(JohnsonColors.BorderSubtle)
            )
            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier.padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Section: 裝置
                SectionLabel("裝置")

                SettingLinkRow(
                    title = "藍牙配對",
                    description = "搜尋並連線手環裝置",
                    onClick = onBluetoothClick
                )

                Spacer(Modifier.height(22.dp))

                // Section: 使用者資料
                SectionLabel("使用者資料")
                Text(
                    text = if (isCustomized) {
                        "用於心率強度區間與熱量估算，下一堂課開始時套用。僅供運動強度監看，非醫療用途。"
                    } else {
                        "目前是 Demo 預設值（30 歲／靜息 65 bpm／男性／70 kg／170 cm），" +
                            "不是任何真實受測者的資料。用於心率強度區間與熱量估算，非醫療用途。"
                    },
                    color = JohnsonColors.TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                StepperRow(
                    title = "年齡",
                    valueText = "${profile.ageYears} 歲",
                    onDecrease = { update(profile.copy(ageYears = profile.ageYears - 1)) },
                    onIncrease = { update(profile.copy(ageYears = profile.ageYears + 1)) },
                    canDecrease = profile.ageYears > UserProfilePreferences.AGE_RANGE.first,
                    canIncrease = profile.ageYears < UserProfilePreferences.AGE_RANGE.last
                )
                StepperRow(
                    title = "靜息心率",
                    valueText = "${profile.restingHeartRateBpm} bpm",
                    onDecrease = { update(profile.copy(restingHeartRateBpm = profile.restingHeartRateBpm - 1)) },
                    onIncrease = { update(profile.copy(restingHeartRateBpm = profile.restingHeartRateBpm + 1)) },
                    canDecrease = profile.restingHeartRateBpm > UserProfilePreferences.RESTING_HR_RANGE.first,
                    canIncrease = profile.restingHeartRateBpm < UserProfilePreferences.RESTING_HR_RANGE.last
                )
                StepperRow(
                    title = "體重",
                    valueText = "${weightKgOf(profile)} kg",
                    onDecrease = { update(profile.copy(weightKg = (weightKgOf(profile) - 1).toFloat())) },
                    onIncrease = { update(profile.copy(weightKg = (weightKgOf(profile) + 1).toFloat())) },
                    canDecrease = weightKgOf(profile) > UserProfilePreferences.WEIGHT_RANGE.first,
                    canIncrease = weightKgOf(profile) < UserProfilePreferences.WEIGHT_RANGE.last
                )
                StepperRow(
                    title = "身高",
                    valueText = "${heightCmOf(profile)} cm",
                    onDecrease = { update(profile.copy(heightCm = (heightCmOf(profile) - 1).toFloat())) },
                    onIncrease = { update(profile.copy(heightCm = (heightCmOf(profile) + 1).toFloat())) },
                    canDecrease = heightCmOf(profile) > UserProfilePreferences.HEIGHT_RANGE.first,
                    canIncrease = heightCmOf(profile) < UserProfilePreferences.HEIGHT_RANGE.last
                )

                val toggleSex = {
                    val next =
                        if (profile.biologicalSex == BiologicalSex.MALE) BiologicalSex.FEMALE else BiologicalSex.MALE
                    update(profile.copy(biologicalSex = next))
                }
                SettingActionRow(
                    title = "生理性別",
                    valueText = if (profile.biologicalSex == BiologicalSex.MALE) "男" else "女",
                    actionLabel = "切換",
                    onClick = toggleSex
                )

                val resetProfile = {
                    preferences.reset()
                    profile = preferences.load()
                    isCustomized = false
                }
                SettingActionRow(
                    title = "還原 Demo 預設值",
                    valueText = "30 歲／65 bpm／男／70 kg／170 cm",
                    actionLabel = "還原",
                    onClick = resetProfile
                )

                Spacer(Modifier.height(22.dp))

                // Section: 錄製上傳
                // 錄製資料頁「上傳到 Google Drive」的中繼設定；電視上沒有 Google 登入，
                // Drive API 沒辦法匿名寫入，所以走使用者自己部署的 Apps Script Web App 當中繼
                // （契約見 README「錄製資料頁」一節）。網址／token 是使用者的私有部署資訊，
                // 只存在 UploadPreferences 的 SharedPreferences，不寫進 repo。
                SectionLabel("錄製上傳")
                Text(
                    text = "設定錄製資料頁「上傳到 Google Drive」用的中繼網址與 token。" +
                        "遙控器打字不方便，開發期建議用 adb 灌值（見 README）。",
                    color = JohnsonColors.TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                SettingActionRow(
                    title = "上傳網址",
                    valueText = uploadUrlSummary(uploadUrl),
                    actionLabel = "編輯",
                    onClick = { editingUploadUrl = true }
                )
                SettingActionRow(
                    title = "上傳 Token",
                    valueText = if (uploadToken.isBlank()) "未設定" else "已設定",
                    actionLabel = "編輯",
                    onClick = { editingUploadToken = true }
                )

                Spacer(Modifier.height(24.dp))
                // Section: 版本
                // 電視上常同時存在好幾次 side-load 的 build，出問題時第一個要問的是「裝的是哪一版」；
                // 建置時間與 commit 由 build.gradle.kts 在建置當下寫進 BuildConfig，不靠人手更新。
                SectionLabel("版本")
                BuildInfoRow()
            }

            Spacer(Modifier.height(32.dp))
        }

        if (editingUploadUrl) {
            UploadFieldEditDialog(
                title = "上傳網址",
                description = "Apps Script Web App 的 /exec 網址，需為 https 開頭（留空即清除）。",
                initialValue = uploadUrl,
                singleLine = true,
                onSave = { newValue ->
                    uploadPreferences.setUploadUrl(newValue).fold(
                        onSuccess = {
                            editingUploadUrl = false
                            null
                        },
                        onFailure = { error -> error.message ?: "上傳網址格式不正確" }
                    )
                },
                onCancel = { editingUploadUrl = false }
            )
        }

        if (editingUploadToken) {
            UploadFieldEditDialog(
                title = "上傳 Token",
                description = "中繼驗證用的 token（留空即清除）。",
                initialValue = uploadToken,
                singleLine = true,
                onSave = { newValue ->
                    uploadPreferences.setUploadToken(newValue)
                    editingUploadToken = false
                    null
                },
                onCancel = { editingUploadToken = false }
            )
        }
    }
}

/** 網址只顯示前 40 字＋刪節號——設定頁不是拿來核對完整網址的地方，只求看得出「有沒有設定、設的像不像」。 */
private fun uploadUrlSummary(url: String): String = when {
    url.isBlank() -> "未設定"
    url.length <= 40 -> url
    else -> "${url.take(40)}…"
}

/**
 * 上傳網址／token 的共用編輯對話框；TV 遙控器打字很痛苦，主要靠 adb 灌值，這裡是備用手段。
 *
 * [onSave] 回傳非 null 代表驗證失敗的錯誤訊息——對話框留著、把原因顯示出來，不關閉；
 * 回傳 null 代表存檔成功，呼叫端自己負責把對話框關掉（例如把 `editingXxx` 設回 false）。
 */
@Composable
private fun UploadFieldEditDialog(
    title: String,
    description: String,
    initialValue: String,
    singleLine: Boolean,
    onSave: (String) -> String?,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    MaterialAlertDialog(
        onDismissRequest = {},
        title = { Text(title, color = JohnsonColors.Gray0) },
        text = {
            Column {
                Text(description, color = JohnsonColors.Gray100, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        errorMessage = null
                    },
                    singleLine = singleLine,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = JohnsonColors.Red400, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            val onConfirm = { errorMessage = onSave(text) }
            Button(onClick = onConfirm, modifier = Modifier.touchClickable(onClick = onConfirm)) {
                Text("儲存")
            }
        },
        dismissButton = {
            Button(onClick = onCancel, modifier = Modifier.touchClickable(onClick = onCancel)) {
                Text("取消")
            }
        },
        containerColor = JohnsonColors.Ink600,
        titleContentColor = JohnsonColors.Gray0,
        textContentColor = JohnsonColors.Gray100
    )
}

private fun weightKgOf(profile: UserProfile): Int =
    (profile.weightKg ?: UserProfilePreferences.DEMO_DEFAULT.weightKg ?: 70f).toInt()

private fun heightCmOf(profile: UserProfile): Int =
    (profile.heightCm ?: UserProfilePreferences.DEMO_DEFAULT.heightCm ?: 170f).toInt()

@Composable
private fun BuildInfoRow() {
    SettingRowFrame {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "建置時間　${BuildConfig.BUILD_TIME}",
                color = JohnsonColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})・" +
                    "${BuildConfig.BUILD_TYPE}・commit ${BuildConfig.GIT_SHA}",
                color = JohnsonColors.TextTertiary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = JohnsonColors.TextTertiary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.14.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun SettingRowFrame(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(JohnsonColors.SurfaceCard)
            .border(1.dp, JohnsonColors.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun SettingLinkRow(title: String, description: String, onClick: () -> Unit) {
    SettingRowFrame {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = JohnsonColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                color = JohnsonColors.TextTertiary,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.width(24.dp))
        Button(onClick = onClick, modifier = Modifier.touchClickable(onClick = onClick)) { Text("前往") }
    }
}

/** 遙控器只有方向鍵，數值調整一律用「−／＋」兩顆按鈕，不用文字輸入。 */
@Composable
private fun StepperRow(
    title: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean
) {
    SettingRowFrame {
        Text(
            text = title,
            color = JohnsonColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = valueText,
            color = JohnsonColors.TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(16.dp))
        Button(
            onClick = onDecrease,
            enabled = canDecrease,
            modifier = Modifier.touchClickable(enabled = canDecrease, onClick = onDecrease)
        ) { Text("−") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onIncrease,
            enabled = canIncrease,
            modifier = Modifier.touchClickable(enabled = canIncrease, onClick = onIncrease)
        ) { Text("＋") }
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    valueText: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    SettingRowFrame {
        Text(
            text = title,
            color = JohnsonColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = valueText,
            color = JohnsonColors.TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(16.dp))
        Button(onClick = onClick, modifier = Modifier.touchClickable(onClick = onClick)) { Text(actionLabel) }
    }
}
