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
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.fitness.activityscoringcore.heart.BiologicalSex
import com.fitness.activityscoringcore.heart.UserProfile
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

    var profile by remember { mutableStateOf(preferences.load()) }
    var isCustomized by remember { mutableStateOf(preferences.isCustomized()) }
    // 每改一項就立刻寫回：這頁沒有「儲存」按鈕，離開畫面（甚至直接進播放頁）都要是已生效的值。
    val update: (UserProfile) -> Unit = { updated ->
        profile = updated
        preferences.save(updated)
        isCustomized = true
    }

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
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun weightKgOf(profile: UserProfile): Int =
    (profile.weightKg ?: UserProfilePreferences.DEMO_DEFAULT.weightKg ?: 70f).toInt()

private fun heightCmOf(profile: UserProfile): Int =
    (profile.heightCm ?: UserProfilePreferences.DEMO_DEFAULT.heightCm ?: 170f).toInt()

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
