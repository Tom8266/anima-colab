package com.tom.animacolab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tom.animacolab.data.ThemeMode

// ---------------------------------------------------------------------------
// 品牌色板（未开启动态取色时的 Anima Expressive 方案）
// 灵感：动画作品的高饱和“电光紫 + 蜜桃粉 + 青柠蓝”，但全部经过 M3
// tonal palette 校准，保证 4.5:1 以上的正文对比度。
// ---------------------------------------------------------------------------

private val BrandLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF5A43C6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF16005E),
    inversePrimary = Color(0xFFC9BFFF),
    secondary = Color(0xFFA2356C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD8E7),
    onSecondaryContainer = Color(0xFF3C0025),
    tertiary = Color(0xFF0E6673),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB5EBFB),
    onTertiaryContainer = Color(0xFF001F25),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFE6E0EA),
    onSurfaceVariant = Color(0xFF48454F),
    surfaceTint = Color(0xFF5A43C6),
    inverseSurface = Color(0xFF302F38),
    inverseOnSurface = Color(0xFFF3EFF9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF787480),
    outlineVariant = Color(0xFFCAC4CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCF8FF),
    surfaceContainer = Color(0xFFF1ECF6),
    surfaceContainerHigh = Color(0xFFEBE6F0),
    surfaceContainerHighest = Color(0xFFE5E1EA),
    surfaceContainerLow = Color(0xFFF7F2FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDD8E3),
    primaryFixed = Color(0xFFE6DEFF),
    primaryFixedDim = Color(0xFFC9BFFF),
    onPrimaryFixed = Color(0xFF16005E),
    onPrimaryFixedVariant = Color(0xFF40308C),
    secondaryFixed = Color(0xFFFFD8E7),
    secondaryFixedDim = Color(0xFFFFB0D1),
    onSecondaryFixed = Color(0xFF3C0025),
    onSecondaryFixedVariant = Color(0xFF7F1E55),
    tertiaryFixed = Color(0xFFB5EBFB),
    tertiaryFixedDim = Color(0xFF99CFDF),
    onTertiaryFixed = Color(0xFF001F25),
    onTertiaryFixedVariant = Color(0xFF004E58),
)

private val BrandDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC9BFFF),
    onPrimary = Color(0xFF2B1A70),
    primaryContainer = Color(0xFF43328B),
    onPrimaryContainer = Color(0xFFE6DEFF),
    inversePrimary = Color(0xFF5A43C6),
    secondary = Color(0xFFFFB0D1),
    onSecondary = Color(0xFF5B113A),
    secondaryContainer = Color(0xFF7A2A52),
    onSecondaryContainer = Color(0xFFFFD8E7),
    tertiary = Color(0xFF99CFDF),
    onTertiary = Color(0xFF00363E),
    tertiaryContainer = Color(0xFF1F4E58),
    onTertiaryContainer = Color(0xFFB5EBFB),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE5E1EA),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFE5E1EA),
    surfaceVariant = Color(0xFF48454F),
    onSurfaceVariant = Color(0xFFCAC4CF),
    surfaceTint = Color(0xFFC9BFFF),
    inverseSurface = Color(0xFFE5E1EA),
    inverseOnSurface = Color(0xFF302F38),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF928F99),
    outlineVariant = Color(0xFF48454F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38373F),
    surfaceContainer = Color(0xFF1E1D25),
    surfaceContainerHigh = Color(0xFF292830),
    surfaceContainerHighest = Color(0xFF34333B),
    surfaceContainerLow = Color(0xFF1A1921),
    surfaceContainerLowest = Color(0xFF0D0D13),
    surfaceDim = Color(0xFF121218),
    primaryFixed = Color(0xFFE6DEFF),
    primaryFixedDim = Color(0xFFC9BFFF),
    onPrimaryFixed = Color(0xFF16005E),
    onPrimaryFixedVariant = Color(0xFF40308C),
    secondaryFixed = Color(0xFFFFD8E7),
    secondaryFixedDim = Color(0xFFFFB0D1),
    onSecondaryFixed = Color(0xFF3C0025),
    onSecondaryFixedVariant = Color(0xFF7F1E55),
    tertiaryFixed = Color(0xFFB5EBFB),
    tertiaryFixedDim = Color(0xFF99CFDF),
    onTertiaryFixed = Color(0xFF001F25),
    onTertiaryFixedVariant = Color(0xFF004E58),
)

/** 排版：使用 M3 Expressive 默认字阶（含 Emphasized 变体），中文走系统字体回退。 */
val AnimaTypography: Typography = Typography()

/** 解析当前应使用的深浅色（跟随系统 / 强制浅色 / 强制深色）。 */
@Composable
fun isAnimaDark(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * 应用主题：Material 3 Expressive + 动态取色（Material You）。
 *
 * @param themeMode 深浅色模式，默认跟随系统
 * @param dynamicColor Android 12+ 是否使用壁纸动态取色
 */
@Composable
fun AnimaTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = isAnimaDark(themeMode)
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme: ColorScheme = when {
        dynamicColor && supportsDynamic ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> BrandDarkScheme
        else -> BrandLightScheme
    }
    val extended = if (dark) DarkExtendedColors else LightExtendedColors

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AnimaShapesTokens,
        typography = AnimaTypography,
    ) {
        CompositionLocalProvider(LocalAnimaExtendedColors provides extended) {
            content()
        }
    }
}
