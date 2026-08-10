package com.johnson.fitness.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput

/**
 * androidx.tv.material3 的 Card/Button 只透過 D-pad（KEYCODE_DPAD_CENTER / ENTER）觸發 onClick，
 * 底層 tvClickable() 完全沒有掛 Modifier.clickable / pointerInput，所以手指在觸控螢幕上點擊
 * 這些元件目前是「完全沒反應」的（見 androidx.tv.material3.SurfaceClickableUtils）。
 *
 * 這個 Modifier 補上觸控 tap 手勢，並在點下時手動 requestFocus()，讓觸控也能重用 tv-material
 * 內建、綁在 focus 狀態上的放大/邊框視覺效果，跟 D-pad 導航時的呈現保持一致。
 *
 * 用法：疊加在既有的 Card(onClick = x, modifier = Modifier.touchClickable(onClick = x)) 上，
 * 不影響、也不取代原本的 D-pad 行為，兩種輸入方式並存。
 */
@Composable
fun Modifier.touchClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    if (!enabled) return this

    val focusRequester = remember { FocusRequester() }
    return this
        .focusRequester(focusRequester)
        .pointerInput(onClick) {
            detectTapGestures(
                onTap = {
                    focusRequester.requestFocus()
                    onClick()
                }
            )
        }
}
