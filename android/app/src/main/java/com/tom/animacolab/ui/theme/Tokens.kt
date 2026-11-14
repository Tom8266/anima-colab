package com.tom.animacolab.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Anima Colab 设计令牌（Material 3 Expressive）。
 *
 * 所有界面统一使用这里的间距 / 形状 / 动效，避免手写魔法数字。
 */
object Spacing {
    /** 2dp：极小间隔（图标与文字内侧） */
    val xxs: Dp = 2.dp

    /** 4dp：图标与文字 */
    val xs: Dp = 4.dp

    /** 8dp：基础网格 */
    val sm: Dp = 8.dp

    /** 12dp：紧凑组件之间 */
    val md: Dp = 12.dp

    /** 16dp：常规块间距 */
    val lg: Dp = 16.dp

    /** 20dp：屏幕左右边距 */
    val xl: Dp = 20.dp

    /** 24dp：大块间距 */
    val xxl: Dp = 24.dp

    /** 32dp：Hero 区块间距 */
    val xxxl: Dp = 32.dp

    /** 屏幕左右安全边距 */
    val screen: Dp = 20.dp

    /** 卡片内边距 */
    val card: Dp = 20.dp
}

/** M3 形状 token：整体比 Material 默认更圆、更有表现力（8 个槽位全部对齐）。 */
val AnimaShapesTokens: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
    largeIncreased = RoundedCornerShape(30.dp),
    extraLargeIncreased = RoundedCornerShape(38.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/** 常用形状：从卡片到胶囊按钮。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object AnimaShape {
    /** 大卡片 / Hero 区 */
    val hero: Shape = RoundedCornerShape(32.dp)

    /** 普通卡片 */
    val card: Shape = RoundedCornerShape(26.dp)

    /** 小卡片 / 列表条目 */
    val cardSmall: Shape = RoundedCornerShape(20.dp)

    /** 输入框 */
    val field: Shape = RoundedCornerShape(18.dp)

    /** 顶栏 / 弹层顶部 */
    val sheetTop: Shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

    /** 胶囊按钮 */
    val pill: Shape = RoundedCornerShape(percent = 50)

    /** 圆点 / 徽标 */
    val full: Shape = RoundedCornerShape(percent = 50)

    /** 表现力装饰形状（M3 Expressive shape library，需在 Composable 中使用） */
    val cookie: Shape
        @Composable get() = MaterialShapes.Cookie9Sided.toShape()

    val softBurst: Shape
        @Composable get() = MaterialShapes.SoftBurst.toShape()

    val sunny: Shape
        @Composable get() = MaterialShapes.Sunny.toShape()

    val flower: Shape
        @Composable get() = MaterialShapes.Flower.toShape()

    val clover: Shape
        @Composable get() = MaterialShapes.Clover4Leaf.toShape()
}

/** 动效 token：弹簧曲线，M3 Expressive 的“会呼吸”的手感。 */
object AnimaMotion {
    // 数值对齐 M3 Expressive motion tokens（MotionScheme.expressive()）：
    // default spatial 0.8/380、fast spatial 0.6/800、effects 1.0/1600。

    /** 默认空间运动（位移/尺寸/形状） */
    val spatial: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.8f,
        stiffness = 380f,
    )

    /** 快速空间运动 */
    val spatialFast: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.6f,
        stiffness = 800f,
    )

    /** 轻微回弹（用于强调出现/强调点按） */
    val springy: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.6f,
        stiffness = 520f,
    )

    /** 透明度/颜色等 effect 动画（不允许 overshoot） */
    val effects: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 1600f,
    )

    /** 尺寸类动画（animateContentSize / expandVertically 需要 IntSize spec） */
    val spatialSize: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.8f,
        stiffness = 380f,
    )

}

/**
 * 语义扩展色：M3 只有 error 语义，这里补充 success / warning，
 * 保证深浅色下都有足够对比度。
 */
@Immutable
data class AnimaExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightExtendedColors = AnimaExtendedColors(
    success = Color(0xFF1B6E3C),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFBDF4D0),
    onSuccessContainer = Color(0xFF04210F),
    warning = Color(0xFF7A4F00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDFA0),
    onWarningContainer = Color(0xFF271900),
)

internal val DarkExtendedColors = AnimaExtendedColors(
    success = Color(0xFF8BE8B0),
    onSuccess = Color(0xFF00391C),
    successContainer = Color(0xFF155232),
    onSuccessContainer = Color(0xFFC6F7D8),
    warning = Color(0xFFFFD28A),
    onWarning = Color(0xFF442B00),
    warningContainer = Color(0xFF5E4000),
    onWarningContainer = Color(0xFFFFE6B8),
)

internal val LocalAnimaExtendedColors = staticCompositionLocalOf { DarkExtendedColors }

/** 在 Composable 中读取语义扩展色：`MaterialTheme.extended.success` */
val MaterialTheme.extended: AnimaExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAnimaExtendedColors.current

/** 状态色调：连接状态、任务状态统一语义。 */
enum class StatusTone { Success, Warning, Error, Info, Neutral }

/** 取某个状态色调的（前景色, 容器色）。 */
@Composable
@ReadOnlyComposable
fun statusToneColors(tone: StatusTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val ext = MaterialTheme.extended
    return when (tone) {
        StatusTone.Success -> ext.success to ext.successContainer
        StatusTone.Warning -> ext.warning to ext.warningContainer
        StatusTone.Error -> scheme.error to scheme.errorContainer
        StatusTone.Info -> scheme.tertiary to scheme.tertiaryContainer
        StatusTone.Neutral -> scheme.onSurfaceVariant to scheme.surfaceContainerHighest
    }
}
