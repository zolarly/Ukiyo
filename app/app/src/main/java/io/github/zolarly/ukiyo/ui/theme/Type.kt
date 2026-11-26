package io.github.zolarly.ukiyo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 标题、数字、印章字用系统衬线体（中文是思源宋体），正文用系统无衬线体。
private val base = Typography()

val Typography = Typography(
    displayMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 52.sp, lineHeight = 60.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 48.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, letterSpacing = 1.sp),
)
