package com.johnson.fitness.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 整套畫面原本完全是照 TV 10-foot 螢幕（≈1280dp 寬）的尺度寫死 dp 數字
 * （大 padding、固定寬高的卡片/面板）。手機螢幕寬度通常只有 360~430dp，直接套用
 * 這些數字會爆版或塞不下。這裡抓「螢幕寬度 < 600dp」當作「窄螢幕（手機）」，
 * 對應 Material Design compact width class 的慣例斷點。
 *
 * 能用 `Modifier.fillMaxWidth().widthIn(max = ...)` 這種比例＋上限的寫法自然縮放的地方
 * 就不需要這個判斷（例如置中卡片）；只有像側邊導覽欄這種「固定 dp 在小螢幕上佔比失衡，
 * 用比例縮放又會小到低於最小觸控尺寸」的情況，才需要明確依斷點切換數值。
 */
@Composable
@ReadOnlyComposable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 600
