@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.johnson.fitness.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.johnson.fitness.ui.theme.JohnsonColors

// 原本的「評分演算法」設定（SettlementAlgorithm）已隨 ActivityScoringCore 改版整個移除
// （新版三個評分面向各自獨立輸出，沒有可切換的結算演算法），故此畫面不再需要 ViewModel/狀態，
// 純粹是靜態的設定入口列表。
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onBluetoothClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.BgApp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp, vertical = 32.dp),
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
                Button(onClick = onBack) { Text("返回") }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp)
                    .height(1.dp)
                    .background(JohnsonColors.BorderSubtle)
            )
            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier.padding(horizontal = 56.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Section: 裝置
                SectionLabel("裝置")

                SettingLinkRow(
                    title = "藍牙配對",
                    description = "搜尋並連線手環裝置",
                    onClick = onBluetoothClick
                )
            }
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
private fun SettingLinkRow(title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(JohnsonColors.SurfaceCard)
            .border(1.dp, JohnsonColors.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        Button(onClick = onClick) { Text("前往") }
    }
}
