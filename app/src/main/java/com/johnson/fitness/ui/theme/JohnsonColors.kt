package com.johnson.fitness.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object JohnsonColors {
    // Ink / surfaces
    val Ink1000 = Color(0xFF05070B)
    val Ink900  = Color(0xFF090C12)   // app background
    val Ink800  = Color(0xFF0E121B)   // base surface
    val Ink700  = Color(0xFF141926)   // card / panel
    val Ink600  = Color(0xFF1B2231)   // raised / hover surface
    val Ink500  = Color(0xFF28303F)   // strong divider, inset
    val Ink400  = Color(0xFF3A4456)

    // Text ramp
    val Gray0   = Color(0xFFFFFFFF)
    val Gray50  = Color(0xFFF4F7FB)
    val Gray100 = Color(0xFFE4EAF3)
    val Gray200 = Color(0xFFC7D0DE)
    val Gray300 = Color(0xFFA4AEC0)
    val Gray400 = Color(0xFF7C8699)
    val Gray500 = Color(0xFF525B6C)
    val Gray600 = Color(0xFF424B5C)

    // Johnson brand red
    val Red300 = Color(0xFFFF6A5E)
    val Red400 = Color(0xFFFF4438)
    val Red500 = Color(0xFFE2231A)    // PRIMARY BRAND
    val Red600 = Color(0xFFC0140D)
    val Red700 = Color(0xFF930E09)
    val Red100a = Color(0x24E2231A)   // 14% tint

    // Energy accent (score / combo / "go")
    val Lime300 = Color(0xFFDBFF7A)
    val Lime400 = Color(0xFFC2F94E)   // SCORE accent
    val Lime500 = Color(0xFFA4E62A)
    val Lime600 = Color(0xFF7FBF12)
    val Lime100a = Color(0x29C2F94E)  // 16% tint

    // Heart-rate zone scale
    val HrZ1 = Color(0xFF3D8BFF)      // very light — rest
    val HrZ2 = Color(0xFF1FC7C7)      // light — fat burn
    val HrZ3 = Color(0xFF57D265)      // moderate — aerobic
    val HrZ4 = Color(0xFFFFA12C)      // hard — threshold
    val HrZ5 = Color(0xFFFF3B30)      // maximum

    // Supporting
    val Blue500  = Color(0xFF3D8BFF)
    val Amber500 = Color(0xFFFFB020)
    val Green500 = Color(0xFF2FBF71)

    // Semantic aliases
    val BgApp        = Ink900
    val SurfaceBase  = Ink800
    val SurfaceCard  = Ink700
    val SurfaceRaised= Ink600
    val SurfaceInset = Ink500

    val TextPrimary   = Gray50
    val TextSecondary = Gray300
    val TextTertiary  = Gray400
    val TextDisabled  = Gray600

    val Brand       = Red500
    val BrandHover  = Red400
    val BrandPress  = Red600
    val BrandTint   = Red100a
    val AccentScore = Lime400
    val AccentTint  = Lime100a

    val BorderSubtle  = Color(0x12FFFFFF)   // 7%
    val BorderDefault = Color(0x1FFFFFFF)   // 12%
    val BorderStrong  = Color(0x38FFFFFF)   // 22%
    val FocusRing     = Lime400

    val SurfaceGlass = Color(0x9E141926)    // ~62% alpha

    val StatusLive    = Red500
    val StatusSuccess = Green500
    val StatusWarning = Amber500

    // Gradients
    val GradBrand = Brush.linearGradient(
        colors = listOf(Color(0xFFFF4438), Color(0xFFE2231A), Color(0xFFC0140D)),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(1f, 1f)
    )
    val GradScore = Brush.linearGradient(
        colors = listOf(Lime300, Lime400, Lime500)
    )
}
