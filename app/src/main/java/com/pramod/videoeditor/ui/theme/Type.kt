package com.pramod.videoeditor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System fonts with Devanagari support (no unlicensed font bundling).
val AppTypography = Typography(
    displayLarge = TextStyle(FontFamily.Default, FontWeight.Black, 40.sp),
    headlineMedium = TextStyle(FontFamily.Default, FontWeight.Bold, 24.sp),
    titleLarge = TextStyle(FontFamily.Default, FontWeight.Bold, 20.sp),
    titleMedium = TextStyle(FontFamily.Default, FontWeight.SemiBold, 16.sp),
    bodyLarge = TextStyle(FontFamily.Default, FontWeight.Normal, 16.sp),
    bodyMedium = TextStyle(FontFamily.Default, FontWeight.Normal, 14.sp),
    labelLarge = TextStyle(FontFamily.Default, FontWeight.Medium, 14.sp),
)
