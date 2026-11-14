# M3 Expressive 设计参考（Anima Colab 重设计）

> **API 基线**：`androidx.compose.material3:1.5.0-rc01`（`android/app/build.gradle.kts:66` 显式锁定；Compose BOM 仍 pin 1.4.0，不要删除该行）。
> **签名来源**：本地 sources jar `.tmp/m3-150/src/commonMain/androidx/compose/material3/*.kt`，并用 `javap` 校验 1.5.0-rc01 二进制（存在性 / 实验标记）。凡标注 **[E]** 的 API 必须 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`；其余在本版本已稳定，**不需要 opt-in**（与 2025 年 1.4.0-alpha 博客不同，别照抄旧文）。
> **分工**：屏幕蓝图、令牌（`Spacing`/`AnimaShape`/`AnimaMotion`/`MaterialTheme.extended`）、共享组件签名见 `docs/DESIGN.md`；本文负责「设计依据 + 组件 API + 交互模式 + 验收」。
> **外部依据**：[Get started](https://m3.material.io/get-started)、[Expressive 发布](https://m3.material.io/blog/building-with-m3-expressive)、[I/O 2026 What's new](https://m3.material.io/blog/whats-new-at-io26)、[Compose-first](https://m3.material.io/blog/material-is-compose-first)、[Motion theming](https://m3.material.io/blog/m3-expressive-motion-theming)、[Color](https://m3.material.io/styles/color/system/overview)、[Typography](https://m3.material.io/styles/typography/overview)、[Shape](https://m3.material.io/styles/shape/overview-principles)、[Motion](https://m3.material.io/styles/motion/overview/how-it-works)、[Spacing](https://m3.material.io/styles/spacing/overview)、[Layout](https://m3.material.io/foundations/layout/layout-overview/overview)、[Compose M3](https://developer.android.com/develop/ui/compose/designsystems/material3)、[1.5.0-rc01 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0-rc01)。

## 1. 原则与 2026 更新

- **M3 Expressive = M3 的扩展包，不是 M4**：更鲜活的颜色、弹性动效、可适配组件、flexible typography、对比形状（[发布文](https://m3.material.io/blog/building-with-m3-expressive)）。
- **研究证据**（46 项研究 / 18,000+ 参与者）：全年龄段更偏好 expressive；在 playfulness/energy/creativity/friendliness 上评分更高；关键 UI 元素被识别速度最高快 4 倍；用户更可能切换到使用 Expressive 组件与手法的产品。→ *表达力服务于可用性，不是装饰*（[研究摘要](https://design.google/library/expressive-material-design-google-research)）。
- **7 条 expressive tactics**（本 App 取用范围）：① 混用形状制造张力 ② 层次化、丰富的 color roles ③ 用 emphasized 字体引导注意力 ④ 用容器分组强调内容 ⑤ 物理弹簧动效 ⑥ 组件灵活性（大屏/横屏）⑦ 用以上组合做 **1–2 个 hero moment**。每个屏幕最多 1 个 hero moment（本 App：生成页出图卡 / 查看器大图 / 设置页一键启动卡）。
- **2026 更新（I/O 2026, 2026-05-19）**：① Expressive layout system（layout scaffold、compact/medium/expanded breakpoints、canonical examples）；② **新 spacing system：8dp 尺度，`space100 = 8dp`，以 padding / gap / margin 三类 token 适配设备与密度**；③ Expressive lists & menus；④ **Material Android 全面 Compose-first**：Material Compose 1.5.0 将在 2026 下半年把 M3 Expressive 从实验 API 提升为稳定，Material Views 1.14.0 是最后一个稳定版并进入维护模式（[What's new](https://m3.material.io/blog/whats-new-at-io26)、[Compose-first](https://m3.material.io/blog/material-is-compose-first)）。
- **落地规则**：8dp 网格（`Spacing.sm=8` 起，4/12/20 作为半档）；页面左右 20dp；卡片内 20dp；块间距 12/16/20/24；不用 4/6/14 等随机值（`docs/DESIGN.md` §5）。

## 2. 颜色

- **26+ color roles**，按强调度和容器关系成对使用：`primary/onPrimary/primaryContainer/onPrimaryContainer`、`secondary`、`tertiary`、`error`、`surface*`、`outline*`、`inverse*`、`*Fixed/*FixedDim`。**永远成对取色**（背景色 + 对应 on 色），不要自己算对比度（[color system](https://m3.material.io/styles/color/system/overview)）。
- **Tonal surfaces（非 elevation）**：`surfaceContainerLowest < Low < (surface) < Container < High < Highest`，`surfaceBright/Dim` 用于高/低层。卡片用 `surfaceContainerLow`，可交互控件/展开区用 `surfaceContainerHigh`，Hero 用 `primaryContainer`（`docs/DESIGN.md` §1）。
- **Dynamic color（Material You）**：`dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`，Android 12+；系统提供 **standard / medium / high 三档对比度**，Compose 无法在 API 里手调档位，跟随系统即可；关闭动态取色时用品牌 `BrandLightScheme/BrandDarkScheme`。
- **1.5.0-rc01 只有 `expressiveLightColorScheme()`，没有 `expressiveDarkColorScheme()`**（已用 `javap` 确认；官方主题样例同样如此）。它的作用只是把浅色主题的 4 个 on-container 角色换成 tone 30（`onPrimaryContainer=Primary30` 等，比默认 tone 10 更鲜艳，即 2024-08 “more colorful text” 更新）；**深色主题本就使用 tone 90 on-container，直接用 `darkColorScheme()` / `dynamicDarkColorScheme()` 即可**。
```kotlin
// import androidx.compose.material3.MaterialExpressiveTheme
// import androidx.compose.material3.expressiveLightColorScheme / darkColorScheme / dynamic*ColorScheme
val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
val scheme = when {
    Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    dark -> darkColorScheme()                       // 深色无 expressive 变体，不要臆造
    else -> expressiveLightColorScheme()            // MaterialExpressiveTheme 的浅色默认值
}
MaterialExpressiveTheme(colorScheme = scheme) { /* content */ }
```
- **禁止硬编码 `Color(0x…)`**（装饰透明除外）。语义色用 `MaterialTheme.extended.success/warning` + `StatusTone`（`docs/DESIGN.md`）；状态不能只靠颜色表达，必须配文字（`StatusPill`）。
- 对比度底线：正文 ≥4.5:1、大字 ≥3:1、非文字（图标/边框/进度条 track）≥3:1；禁用态可放宽。浅/深色两套都要看截图验收。

## 3. 字体

- M3 type scale = **15 个 baseline + 15 个 emphasized = 30 个样式**；emphasized 是「同一字号/行高、更重字重、tracking 按样式微调（display/title 收敛、body 略变）」的强调变体（[type scale 更新](https://m3.material.io/styles/typography/overview)）。2026-08 起还支持按语言 script 高度自动调整行高（CJK 属 high/extra-high 档，组件可自适应高度）。
- 在 Compose 中：`Typography()` 主构造器会把 15 个 emphasized 字段填成设计 token；**只有当传入 `fontFamily` 的次构造器（`Typography(fontFamily = X)`）才会让 emphasized 退化成普通样式**——如果以后给 App 打包中文字体，必须显式把 `*Emphasized` 一起传入，否则 `headlineMediumEmphasized` 视觉上等于普通字重（gotcha）。
- 关键 token（size / lineHeight / weight / tracking，baseline → emphasized）：
  `displayLarge` 57/64/Regular/-0.2 → **Medium/0**；`headlineMedium` 28/36/Regular/0 → **Medium/0**；`titleLarge` 22/28/Regular/0 → **Medium/0**；`titleMedium` 16/24/Medium/0.2 → **Bold/0.15**；`labelLarge` 14/20/Medium/0.1 → **Bold/0.1**；`bodyLarge` 16/24/Regular/0.5 → **Medium/0.15**；`bodyMedium` 14/20/Regular/0.2 → **Medium/0.25**；`bodySmall` 12/16/Regular/0.4 → **Medium/0.4**；`labelSmall` 11/16/Medium/0.5 → **Bold/0.5**。
- 用法：每屏 1 个 `ScreenHeader` 用 `MaterialTheme.typography.headlineMediumEmphasized`；卡片标题 `titleMedium`（重点用 `titleMediumEmphasized`）；正文 `bodyMedium`；元数据 `labelSmall`；**不要用 displayLarge**（移动端过大）。`docs/DESIGN.md` 已封装 `ScreenHeader/SectionLabel/MetaText`，优先用。
- **CJK 注意**：① 中文字形来自系统 sans-serif fallback（Android 为 Noto Sans CJK），`FontFamily.SansSerif` 即可，不要为中文强塞 Roboto；② 拉丁 tracking（bodyLarge 0.5sp、labelSmall 0.5sp）对中文偏散，**中文正文/标签建议 `letterSpacing = 0.sp` 或最多 0.1sp**（在 `Text` 上覆盖 style 即可，不要改全局 Typography）；③ 中文没有西文那样的字重断层，emphasized 用 Bold 时注意 11–12sp label 会糊，`labelSmallEmphasized` 只用于关键数字/短标签；④ 行高：中文建议 ≥1.5×字号（bodyLarge 已有 24/16=1.5 ✓），多行提示词/日志不要压行高；⑤ 等宽元数据用 `FontFamily.Monospace`（`MetaText` 已处理），仅用于 seed/尺寸/日志。

## 4. 形状

- **corner scale**（M3 官方 token）：xs 4dp / s 8dp / m 12dp / l 16dp / **lIncreased 20dp** / xl 28dp / **xlIncreased 32dp** / **xxl 48dp** / full = `CircleShape`（不再用 50% 模拟）。App 通过 `AnimaShapesTokens` 整体放大（`MaterialTheme.shapes`），屏幕代码请优先用 `AnimaShape.card/hero/cardSmall/field/pill`，不要手写 dp。
- **shape library = `MaterialShapes`，35 个 `RoundedPolygon`**（已 normalized）：`Circle Square Slanted Arch Fan Arrow SemiCircle Oval Pill Triangle Diamond ClamShell Pentagon Gem Sunny VerySunny Cookie4/6/7/9/12Sided Ghostish Clover4/8Leaf Burst SoftBurst Boom SoftBoom Flower Puffy PuffyDiamond PixelCircle PixelTriangle Bun Heart`。装饰性用途（头像遮罩、图片裁切、徽标、EmptyState 插画、Hero 背景），**不要给功能语义硬绑形状**（shape 是可变的表达，不是语义）。
- 形状原则：shape morph 只用于 **交互状态 / 进行中 / 环境变化**；用「圆 × 方」对比制造 tension；抽象形状要克制（每个屏幕 1–2 处），避免小形状让关键操作显得不重要；与字体的圆润度保持一致。
- 代码见 §6.13。`toShape()` 是 `@Composable`（内部 `remember`），**必须在 Composable 里取**；非 Composable 处改用 `ShapeDecoration`/`clip`。`@OptIn` 仅在 shape 相关 API 需要。
- 按压/选中 morph：`ToggleButton`/`Button`/`SplitButton` 已内置 shapes 切换；自定义组件用 `AnimaShape` 两态 + `AnimaMotion.spatialFast` 做 10–16% 圆角变化即可。

## 5. 动效

- **motion physics 取代 easing+duration**：`MotionScheme` 只暴露 6 个 spring spec，分 **spatial**（位移/尺寸/旋转/形状/圆角，可轻微 overshoot）与 **effects**（颜色/透明度，不允许 overshoot），各 3 档速度 default/fast/slow（[motion system](https://m3.material.io/styles/motion/overview/how-it-works)）。
- 内置值（1.5.0-rc01 token，`dampingRatio / stiffness`）——**expressive**：default spatial `0.8/380`、fast spatial `0.6/800`、slow spatial `0.8/200`、effects `1.0/1600`、fast effects `1.0/3800`、slow effects `1.0/800`；**standard**：`0.9/700`、`0.9/1400`、`0.9/300`…。`MaterialExpressiveTheme` 默认注入 `MotionScheme.expressive()`（`docs/DESIGN.md` 已用），**组件内部已自动使用**，屏幕代码只需让自定义动画也走同一套。
- 使用方式：`MaterialTheme.motionScheme.fastSpatialSpec<Float>()` / `defaultEffectsSpec<Int>()` 等，**不要**在空间动画里写 `tween(300)`；颜色/透明度用 effects spec。项目令牌 `AnimaMotion.spatial/spatialFast/springy/effects` 可直接用于 `animate*AsState`/`animateContentSize`，`springy` 用于出现/强调回弹。
- 规则：① 只服务空间关系（从哪来到哪去、层级如何变化），不为炫技；② **单个动画 ≤500ms 等效时长**（弹簧以其 fast/default 档为限，避免 slow effects）；③ 尊重系统「移除动画」时可降级为淡入（检查 `LocalMotionDurationScale`）；④ 列表项入场/状态切换用 `AnimatedContent`/`animateItem`，展开收起用 `animateContentSize`；⑤ 中断/重试优先用弹簧（可 retarget），不要把整屏重置。
- 反例：给每个 IconButton 加缩放、滚动时随机飞入、长于 1s 的 hero 动画、对 `progress` 每帧做 tween（进度用 `WavyProgressIndicatorDefaults.ProgressAnimationSpec` 或 spring with `visibilityThreshold`）。

## 6. 组件 API 速查（1.5.0-rc01）

统一 import：`androidx.compose.material3.*`（图标 `androidx.compose.material.icons.outlined|filled.*`，`automirrored` 用于方向性图标）；片段里的 `Spacing`/`AnimaShape`/`AnimaMotion` 来自 `com.tom.animacolab.ui.theme`（见 `docs/DESIGN.md`）。

**opt-in 总表**：仅 **[E]** = `LoadingIndicator`、`ContainedLoadingIndicator`、`LoadingIndicatorDefaults`、`MaterialShapes`、`toShape()`/`toPath()`、`Morph.toPath()`。其余本版全部稳定：`MaterialExpressiveTheme`、`MotionScheme.expressive()`、`expressiveLightColorScheme()`、`ShortNavigationBar*`、`toggle 按钮族`、`ButtonGroup`、`HorizontalFloatingToolbar`、`FloatingActionButtonMenu`、`SplitButtonLayout`、`Linear/CircularWavyProgressIndicator`。

### 6.1 MaterialExpressiveTheme
```kotlin
@Composable fun MaterialExpressiveTheme(colorScheme: ColorScheme? = null, motionScheme: MotionScheme? = null,
    shapes: Shapes? = null, typography: Typography? = null, content: @Composable () -> Unit)
```
片段见 §2。坑：4 个参数都是**可空**；`null` 时浅色回退 `expressiveLightColorScheme()`、motion 回退 `expressive()`、typography 回退 `Typography()`、shapes 回退 `Shapes()`。深色必须显式传 colorScheme（否则浅色主题）。

### 6.2 MotionScheme
```kotlin
@Immutable interface MotionScheme {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T>; fun <T> fastSpatialSpec(): FiniteAnimationSpec<T>; fun <T> slowSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T>; fun <T> fastEffectsSpec(): FiniteAnimationSpec<T>; fun <T> slowEffectsSpec(): FiniteAnimationSpec<T>
    companion object { fun standard(): MotionScheme; fun expressive(): MotionScheme }
}
```
```kotlin
val scale by animateFloatAsState(if (pressed) .97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec<Float>(), label = "press")
val alpha by animateFloatAsState(if (visible) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec<Float>(), label = "fade")
```
坑：`MaterialTheme.motionScheme` 是 `@Composable @ReadOnlyComposable`；`animateDpAsState` 配 `fastSpatialSpec<Dp>()`；自定义 scheme 可实现该接口（Level 2 定制，本 App 不需要）。

### 6.3 ShortNavigationBar + Item + Arrangement
```kotlin
@Composable fun ShortNavigationBar(modifier: Modifier = Modifier, containerColor: Color = ShortNavigationBarDefaults.containerColor,
    contentColor: Color = ShortNavigationBarDefaults.contentColor, windowInsets: WindowInsets = ShortNavigationBarDefaults.windowInsets,
    arrangement: ShortNavigationBarArrangement = ShortNavigationBarDefaults.arrangement, content: @Composable () -> Unit)

@Composable fun ShortNavigationBarItem(selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit,
    label: @Composable (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true,
    iconPosition: NavigationItemIconPosition = NavigationItemIconPosition.Top,
    colors: NavigationItemColors = ShortNavigationBarItemDefaults.colors(), interactionSource: MutableInteractionSource? = null)

// ShortNavigationBarArrangement.EqualWeight（手机竖屏默认） / .Centered（中/大屏），value class
```
```kotlin
ShortNavigationBar {
    tabs.forEachIndexed { i, tab -> ShortNavigationBarItem(
        selected = i == selected, onClick = { selected = i },
        icon = { Icon(if (i == selected) tab.filled else tab.outlined, contentDescription = null) },
        label = { Text(tab.zh) }) }
}
```
坑：① 手机 3–5 项，用默认 `EqualWeight`；`Centered` 且 `iconPosition=Start` 只用于中/大屏横屏。② label 建议始终提供（选中/未选中都显示）。③ 组件已含系统栏 insets，放进 `Scaffold(bottomBar=)` 或 Column 底部，**不要**再手动 `navigationBarsPadding()`（会双倍留白）。④ 容器是带 tonal 色的 `Surface`，不需要额外加 Card。

### 6.4 LoadingIndicator / ContainedLoadingIndicator **[E]**
```kotlin
@ExperimentalMaterial3ExpressiveApi @Composable
fun LoadingIndicator(progress: () -> Float, modifier: Modifier = Modifier, color: Color = LoadingIndicatorDefaults.indicatorColor,
    polygons: List<RoundedPolygon> = LoadingIndicatorDefaults.DeterminateIndicatorPolygons)
@ExperimentalMaterial3ExpressiveApi @Composable
fun LoadingIndicator(modifier: Modifier = Modifier, color: Color = LoadingIndicatorDefaults.indicatorColor,
    polygons: List<RoundedPolygon> = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons)
// ContainedLoadingIndicator 同构，另有 containerColor / indicatorColor / containerShape 参数
```
```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val p by animateFloatAsState(state.progress, WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "p")
ContainedLoadingIndicator(progress = { p })                 // 页内卡片/下拉刷新
LoadingIndicator(Modifier.size(22.dp), color = LocalContentColor.current) // 按钮内（无 progress = 不定态）
```
坑：① determinate 的 `progress` 是 **lambda**（延迟读取，避免整个卡片每帧重组）；② 不定态默认在 7 个 MaterialShapes 间 morph，determinate 在 2 个形状间随进度 morph，可传自定义 `polygons`（≥2 项，否则 `IllegalArgumentException`）；③ `LoadingIndicatorDefaults` 本身也带 [E]，读取默认色/形状也要 opt-in；④ 已内置 progress 语义，不要给外层再叠 `progressSemantics`。

### 6.5 LinearWavyProgressIndicator / CircularWavyProgressIndicator（稳定，无需 opt-in）
```kotlin
@Composable fun LinearWavyProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier, color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor, stroke: Stroke = WavyProgressIndicatorDefaults.linearIndicatorStroke,
    trackStroke: Stroke = WavyProgressIndicatorDefaults.linearTrackStroke, gapSize: Dp = ..., stopSize: Dp = ...,
    amplitude: (progress: Float) -> Float = WavyProgressIndicatorDefaults.indicatorAmplitude,
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength, waveSpeed: Dp = wavelength)

@Composable fun LinearWavyProgressIndicator(modifier: Modifier = Modifier, color: Color = ..., trackColor: Color = ...,
    stroke: Stroke = ..., trackStroke: Stroke = ..., gapSize: Dp = ..., @FloatRange(0.0, 1.0) amplitude: Float = 1f,
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearIndeterminateWavelength, waveSpeed: Dp = wavelength)
// CircularWavyProgressIndicator 同构（determinate 有 progress；不定态只有 modifier/color/trackColor/stroke/amplitude/wavelength）
```
```kotlin
val p by animateFloatAsState(state.progress, WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "gen")
LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())   // 生成中：确定进度
LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())                     // 部署/连接：不定态
CircularWavyProgressIndicator(progress = { p }, modifier = Modifier.size(96.dp))    // 环形 hero 进度
```
坑：① 默认容器 linear 240×10dp、circular 48dp（track 厚 4dp），**linear 要铺满请传 `Modifier.fillMaxWidth()`**，circular 用 `Modifier.size()`；② 默认不自动 animate 进度，必须自己套 `ProgressAnimationSpec`（tween 500ms）或 spring + `visibilityThreshold`；③ **track 与背景对比度 <3:1 时 stop indicator 必须保留**（`stopSize` 默认已开，别设 0）；④ determinate 已内置 `ProgressBarRangeInfo` 语义，不定态用 `progressSemantics()`；⑤ 旧 `LinearProgressIndicator/CircularProgressIndicator` 仍可用但不再是 expressive 首选，新代码统一用 wavy。

### 6.6 ToggleButton + ToggleButtonDefaults（稳定）
（`Button/OutlinedButton/ElevatedButton/FilledTonalButton` 也有 `shapes: ButtonShapes` 重载，按压形状变换用 `ButtonDefaults.shapesFor(height)`（@Composable）；`PrimaryActionButton` 已封装。）
```kotlin
@Composable fun ToggleButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    buttonSize: ToggleButtonSize = ToggleButtonDefaults.size, enabled: Boolean = true, icon: @Composable (() -> Unit)? = null,
    shapes: ToggleButtonShapes = ToggleButtonDefaults.shapesFor(buttonSize), colors: ToggleButtonColors = ToggleButtonDefaults.colors(),
    elevation: ToggleButtonElevation? = ToggleButtonDefaults.elevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ToggleButtonDefaults.contentPaddingFor(buttonSize, hasStartIcon = icon != null),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit)
// ToggleButtonSize: ExtraSmall/Small/Medium/Large/ExtraLarge；变体：Elevated/FilledTonal/OutlinedToggleButton
```
```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    ToggleButton(checked = !negative, onCheckedChange = { if (it) vm.setInsertPositive(true) }) { Text("正向") }
    ToggleButton(checked = negative, onCheckedChange = { if (it) vm.setInsertPositive(false) }) { Text("负向") }
}
```
坑：① `content` 是 `RowScope`，多元素自己加 `Spacer`；② 视觉高度 Small=40dp，但 Surface 已强制 48dp 最小交互尺寸（a11y 达标，别再说触控太小）；③ 未选中=tonal 容器、选中=primary 容器+方角 morph，**同一组语义相反的选项用互斥 checked**；④ 需要「连接式」分段选择时不要堆 ToggleButton，用 ButtonGroup（6.8）。

### 6.7 ButtonGroup + ButtonGroupDefaults（稳定）— overflowIndicator 必填
```kotlin
@Composable fun ButtonGroup(overflowIndicator: @Composable (ButtonGroupMenuState) -> Unit, modifier: Modifier = Modifier,
    @FloatRange(0.0) expandedRatio: Float = ButtonGroupDefaults.ExpandedRatio,      // 0.15f
    horizontalArrangement: Arrangement.Horizontal = ButtonGroupDefaults.HorizontalArrangement,
    verticalAlignment: Alignment.Vertical = Alignment.Top, content: ButtonGroupScope.() -> Unit)

sealed interface ButtonGroupScope {   // DSL：Modifier.weight / animateWidth / align
    fun clickableItem(onClick: () -> Unit, label: String, icon: (@Composable () -> Unit)? = null, weight: Float = Float.NaN, enabled: Boolean = true)
    fun toggleableItem(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit, icon: (@Composable () -> Unit)? = null, weight: Float = Float.NaN, enabled: Boolean = true)
    fun customItem(buttonGroupContent: @Composable () -> Unit, menuContent: @Composable (ButtonGroupMenuState) -> Unit)
}
```
```kotlin
var size by rememberSaveable { mutableStateOf("1024×1024") }
val presets = listOf("1024×1024", "832×1216", "1216×832", "1080×1920")
ButtonGroup(overflowIndicator = { m -> ButtonGroupDefaults.OverflowIndicator(menuState = m) }) {
    presets.forEach { p -> toggleableItem(checked = size == p, label = p, onCheckedChange = { if (it) size = p }) }
}
```
坑：① **`overflowIndicator` 是必填第一参数**，推荐直接用 `ButtonGroupDefaults.OverflowIndicator(menuState = m)`；项太多/太宽会自动收进 DropdownMenu，但当前可见项数需自己保证 ≤4（手机 392dp）。② 只有 `ButtonGroupDefaults.OverflowIndicator` 会调用 `menuState.show()/dismiss()`，自绘必须自己调。③ 连接式单选组（形状两端圆、中间方）用 `customItem { ToggleButton(shapes = ButtonGroupDefaults.connectedLeading/Middle/TrailingButtonShapes(...), interactionSource = src, modifier = animateWidth(src, compressionLimit)) }`，并给每个 item 独立 `MutableInteractionSource`。④ `expandedRatio` 是按压膨胀比例，0f 关闭动画；与 `Modifier.pressScale` 不要叠加。

### 6.8 HorizontalFloatingToolbar + FloatingToolbarDefaults（稳定）
```kotlin
@Composable fun HorizontalFloatingToolbar(expanded: Boolean, modifier: Modifier = Modifier,
    colors: FloatingToolbarColors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    contentPadding: PaddingValues = FloatingToolbarDefaults.ContentPadding, scrollBehavior: FloatingToolbarScrollBehavior? = null,
    shape: Shape = FloatingToolbarDefaults.ContainerShape, leadingContent: @Composable (RowScope.() -> Unit)? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null, expandedShadowElevation: Dp = ..., collapsedShadowElevation: Dp = ...,
    content: @Composable RowScope.() -> Unit)
// 另有带 floatingActionButton + floatingActionButtonPosition + animationSpec 的重载；Vertical 版本同构
```
```kotlin
HorizontalFloatingToolbar(
    expanded = true,
    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.md),
    content = {
        IconButton(onClick = onSave) { Icon(Icons.Outlined.Download, contentDescription = "保存") }
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = "分享") }
        IconButton(onClick = onFillParams) { Icon(Icons.Outlined.Tune, contentDescription = "填回参数") }
    })
```
坑：① TalkBack 等触摸探索服务开启时组件**强制展开**（内置），所以 `expanded=false` 的折叠态不要承担唯一入口；② Dialog 内使用要自己加 `statusBarsPadding()/navigationBarsPadding()` 并注意层级（`zIndex`）；③ 图标按钮必须 `contentDescription`；要「图标+文案」时在 RowScope 里放自定义 Column(Icon, Text(labelSmall)) 并加 `Role.Button` 语义；④ 滚动页面时可选 `scrollBehavior`（`FloatingToolbarDefaults.exitAlwaysScrollBehavior(...)` + `rememberFloatingToolbarState`）把工具栏藏起来，查看器不需要。

### 6.9 FloatingActionButtonMenu + ToggleFloatingActionButton + Item（稳定）
```kotlin
@Composable fun FloatingActionButtonMenu(expanded: Boolean, button: @Composable () -> Unit, modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.End, content: @Composable FloatingActionButtonMenuScope.() -> Unit)

@Composable fun ToggleFloatingActionButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    containerColor: (Float) -> Color = ToggleFloatingActionButtonDefaults.containerColor(), contentAlignment: Alignment = Alignment.TopEnd,
    containerSize: (Float) -> Dp = ToggleFloatingActionButtonDefaults.containerSize(),
    containerCornerRadius: (Float) -> Dp = ToggleFloatingActionButtonDefaults.containerCornerRadius(),
    content: @Composable ToggleFloatingActionButtonScope.() -> Unit)   // scope 暴露 checkedProgress: Float

@Composable fun FloatingActionButtonMenuScope.FloatingActionButtonMenuItem(onClick: () -> Unit, text: @Composable () -> Unit,
    icon: @Composable () -> Unit, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer, contentColor: Color = contentColorFor(containerColor))
```
```kotlin
Box(Modifier.fillMaxSize()) {                    // FAB 菜单自己不定位置，必须用 Box + align
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    FloatingActionButtonMenu(expanded = expanded, modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.screen),
        button = {
            ToggleFloatingActionButton(checked = expanded, onCheckedChange = { expanded = it }) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add } }
                Icon(icon, contentDescription = if (expanded) "收起操作" else "更多操作",
                    modifier = Modifier.animateIcon({ checkedProgress }))   // import ...ToggleFloatingActionButtonDefaults.animateIcon
            }
        }) {
        FloatingActionButtonMenuItem(onClick = { expanded = false; onRefresh() },
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) }, text = { Text("刷新图库") })
    }
}
```
坑：① Item 是 **scope 扩展函数**，只能写在 menu 的 content lambda 里；② icon 必填（不能省略）；③ FAB 图标用 `Modifier.animateIcon` 跟随 `checkedProgress` 变色变大小，加/关图标切换在 0.5 处；④ `BackHandler` + 键盘/焦点/TalkBack 的自定义动作在示例里是「建议做」，至少要做 BackHandler 与 `contentDescription/stateDescription`；⑤ 滚动隐藏可加 `Modifier.animateFloatingActionButton(visible, Alignment.BottomEnd)`（FAB 菜单官方样例）。

### 6.10 SplitButtonLayout + SplitButtonDefaults（稳定）
```kotlin
@Composable fun SplitButtonLayout(leadingButton: @Composable () -> Unit, trailingButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, spacing: Dp = SplitButtonDefaults.Spacing)

// SplitButtonDefaults: LeadingButton(onClick, ...) / TonalLeadingButton / OutlinedLeadingButton / ElevatedLeadingButton
// TrailingButton(onClick, ...) 或 TrailingButton(checked, onCheckedChange, ...)（可切换箭头）
// 另含 LeadingIconSize / TrailingIconSize / Spacing / *InnerCornerSize / *ButtonShapes
```
```kotlin
var menu by remember { mutableStateOf(false) }
Box {
    SplitButtonLayout(
        leadingButton = { SplitButtonDefaults.LeadingButton(onClick = onGenerate) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text("生成")
        } },
        trailingButton = { SplitButtonDefaults.TrailingButton(checked = menu, onCheckedChange = { menu = it }) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "更多生成选项", modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize))
        } })
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("重试（新种子）") }, onClick = { menu = false; onRetryNewSeed() })
    }
}
```
坑：**没有 `SplitButton` 这个 composable**，必须 `SplitButtonLayout` + 两个 builder 拼；trailing 的下拉菜单要自己放进 Box 并锚定，菜单项 ≥2 时才值得用 SplitButton；整体只应出现在 hero 操作（生成/保存）上，同级不要超过 1 个。

### 6.11 expressiveLightColorScheme（稳定）
```kotlin
fun expressiveLightColorScheme(): ColorScheme     // 无参数；没有 expressiveDarkColorScheme
```
用法见 §2。坑：不要从 1.4.0-alpha 博客/示例复制 `expressiveDarkColorScheme()`，该符号在 1.5.0-rc01 中不存在，会直接编译失败。

### 6.12 Typography emphasized 字段（稳定）
```kotlin
public class Typography(/* 15 个 baseline + 15 个 emphasized 字段，均为 TextStyle */)
```
字段：`displayLargeEmphasized displayMediumEmphasized displaySmallEmphasized headlineLargeEmphasized headlineMediumEmphasized headlineSmallEmphasized titleLargeEmphasized titleMediumEmphasized titleSmallEmphasized bodyLargeEmphasized bodyMediumEmphasized bodySmallEmphasized labelLargeEmphasized labelMediumEmphasized labelSmallEmphasized`。
```kotlin
Text("Anima", style = MaterialTheme.typography.headlineMediumEmphasized)
Text("30 步 · CFG 4.5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
```
坑：见 §3 的 `Typography(fontFamily=…)` 退化问题；`MaterialTheme.typography` 只能在 Composable 里读。

### 6.13 MaterialShapes + RoundedPolygon.toShape() **[E]**
```kotlin
@ExperimentalMaterial3ExpressiveApi public sealed class MaterialShapes { companion object { /* 35 个 RoundedPolygon val */ } }
@ExperimentalMaterial3ExpressiveApi @Composable fun RoundedPolygon.toShape(startAngle: Int = 0): Shape
@ExperimentalMaterial3ExpressiveApi @Composable fun RoundedPolygon.toPath(startAngle: Int = 0): Path
@ExperimentalMaterial3ExpressiveApi fun Morph.toPath(progress: Float, path: Path = Path(), startAngle: Int = 0): Path
```
```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class) @Composable
fun HeroBlob() = Box(Modifier.size(120.dp).clip(MaterialShapes.SoftBurst.toShape())
    .background(MaterialTheme.colorScheme.primaryContainer))
// 形状 morph：remember { Morph(MaterialShapes.Circle, MaterialShapes.Heart) }，再 drawPath(morph.toPath(progress))
```
坑：① `MaterialShapes` 是 sealed class（不可实例化），`object` 用 companion；② 每个 shape 已 normalized，缩放到容器时是「等比居中」，可安全用于任意尺寸；③ `toShape()` 内部 remember，**不要**放在 `remember{}` 里缓存成变量再跨 composition 复用（会失去 remember 语义，用 `AnimaShape.xxx` 那种 `@Composable get()` 模式）；④ **import `androidx.compose.material3.toShape`（扩展函数）**，`RoundedPolygon`/`Morph` 来自 `androidx.graphics.shapes`（material3 的 api 依赖已暴露，无需额外加依赖）；⑤ 装饰形状透明度 ≤0.2，只做背景，不进内容层。

## 7. 按任务的交互模式

### 7.1 提示词编排（GenerateScreen 核心）
- 层次：`ScreenHeader`（标题/状态行/刷新）→ Hero 卡（未配对时）→ 模型 `ChoiceChips` → **提示词卡（视觉最重，ExpressiveCard + `surfaceContainerLow`）** → 参数卡（可折叠）→ 生成 SplitButton → 进度/结果卡。
- 输入：正向 `AnimaTextField(minLines=4, maxLines=8)`；负向默认折叠（`AnimatedChevron` + `animateContentSize(AnimaMotion.spatial)`），展开后一个 `AnimaTextField`；`MetaText` 显示字符数/质量前缀说明；生成中禁用输入但保留可滚动。
- 参数：步数/CFG 用 `AnimaSliderRow`（数值右对齐）；尺寸用 **ButtonGroup**（4 个预设 + 溢出菜单，选中态 morph 对齐 6.7）；种子输入 + 随机 IconButton；张数用 stepper（icon button + text）。
- 生成动作：hero 级只放 **一个** SplitButton——主按钮「生成」，下拉「重试（新种子）/ 清空队列」；未连接时 disabled + 文案「连接后生成」；生成中 loading 用 `LoadingIndicator`（按钮内 22dp，见 6.4）。
- 键盘/焦点：整页 `verticalScroll` + `imePadding()`；生成前 `FocusManager.clearFocus()`；错误信息放在输入框下方（`supportingText` 位置）而不是弹窗。

### 7.2 长时间生成进度（65–90s 任务）
- 状态映射：`Submitting/Queued` → 不定态 `LinearWavyProgressIndicator()` + 队列位置；`Running` → determinate（`progress = { animated }`）+ 百分比 + 已用时长 + 「中断」；`Success` → hero 大图（shape=`AnimaShape.hero`，点击进查看器）+ 保存/分享/填回参数；`Failed` → `errorContainer` 卡 + 重试/关闭。
- 性能：进度事件（WS `progress.value/max`）在 ViewModel 转成 `Float`；UI 端 `animateFloatAsState(progress, ProgressAnimationSpec)` 再喂 lambda；**只让进度卡这一小块重组**（`derivedStateOf` 或单独 Composable 参数化），不要用每 200ms 的 tick 触发整屏。
- 反馈层级：进度条（视觉）+ 文字状态（可达）+ 系统通知（后台，已有 `Notifications`）；「中断」是 destructive text button，点击后加确认对话框，防止误触。
- 动效：progress 变化走 `WavyProgressIndicatorDefaults.ProgressAnimationSpec`；卡出现/切换用 150–250ms 等效 effects 淡入 + 8dp 位移（spatial fast）；**不要**让进度卡每次 tick 抖动/缩放。
- 边界：WS 断线时显示「重连中」但不打断轮询兜底（已有逻辑）；完成瞬间播放一次 hero 强调（回弹 ≤400ms）后稳定，不循环。

### 7.3 图库与查看器
- 图库：`ScreenHeader("图库", subtitle="${n} 张", actions={刷新})`；3 个状态：加载中 → 居中 `LoadingIndicator`；空 → `EmptyState("还没有生成记录", action="去生成")`；错误 → 错误卡 + 重试。数据 → `LazyVerticalStaggeredGrid(2 列)`，卡片 `ExpressiveCard(onClick, shape=cardSmall)`，图片按 `params.width/height` 设 `aspectRatio` 防跳动，多图角标，meta 用 `MetaText`。
- 查看器是 **hero moment**：全屏 Dialog + 深色 scrim + `HorizontalPager`（`ContentScale.Fit`）；顶部半透明胶囊（关闭/时间/页码，`statusBarsPadding()`）；底部 `HorizontalFloatingToolbar(expanded = true)` 放 保存 / 分享 / 填回参数（图标按钮 + contentDescription）；参数元数据小字放工具栏上方；关闭时 `BackHandler` 调 `vm.closeViewer()`。
- 交互细节：保存成功用 Snackbar（带「查看相册」action）；分享复用已下载字节（已有逻辑）；**填回参数**要 clamp 到当前模型支持的尺寸/步数，失败时提示而不是静默。
- 大图加载：`NetworkImage` 已带缩采样/字节缓存；查看器用全尺寸，加载中显示 `CircularWavyProgressIndicator(progress={p})` 或占位 shimmer，错误显示重试按钮。

### 7.4 Tag 速查与插入
- 结构：`ScreenHeader("速查", subtitle="${tags} 个 Tag · ${templates} 套模板")` → 模式切换 `SegmentedOptions(["Tag 速查","模板"])` → 搜索框（`AnimaTextField(leadingIcon=Search)`）→ 插入位置 `ToggleButton` 对（正向/负向，6.6）→ 分类 `LazyRow` chip（`FilterChip`/`ChoiceChips`，颜色全走主题）→ 常用片段 `AssistChip` 行 → `LazyColumn` Tag 列表。
- Tag 行：整行可点（`pressScale` 反馈）+ 右侧添加 IconButton（同一动作、都要 `Role.Button` 语义），显示中文名 + 英文 `MetaText` + 分类 + artist 用 `secondary` 标记；点击后插入提示词并 `Snackbar`「已加入正向提示词」，保持焦点不跳。
- 性能：288 tags，`LazyColumn(key = tag.en)`、搜索/分类过滤用 `derivedStateOf` 的 `remember`；若一帧要做 4 位 tag 过滤（设计示例），用 `derivedStateOf` 避免每次重组过滤。
- 空态：搜索无结果 → `EmptyState("没有匹配的 Tag", action="清除搜索")`；模板空 → `EmptyState("没有模板", action="去生成并保存")`。

### 7.5 设置 / 连接（一键启动 + 手动配对）
- 结构：`ScreenHeader("设置", "外观 · 连接 · 数据")` + `SectionLabel` + `ExpressiveCard` 分组：① 一键启动 Hero（状态机）② 外观 ③ 通知与保存 ④ 手动配对（折叠）⑤ 服务器信息 ⑥ 怎么用 ⑦ 关于。
- 连接状态机（页内最重的信息）：`Off` → Hero primaryContainer + 主按钮；`Starting/Deploying` → `ContainedLoadingIndicator` + 不定态 `LinearWavyProgressIndicator` + 当前 stage + 最近 3 行日志（`MetaText`，容器 `surfaceContainerHigh`，**不要**设 liveRegion 逐行播报）+ 取消；`Ready` → `StatusPill(Success)` + 设备/显存/队列 `InfoRow` + 停止会话（destructive）；`Failed` → `errorContainer` + 原因 + 重试（不要只显示异常字符串，映射为中文可执行建议）。
- 外观：`SegmentedOptions(["跟随系统","浅色","深色"])` + `SettingSwitchRow("动态取色（Material You)")` + 3 个圆形色板预览（primary/secondary/tertiary，说明「来自壁纸」）；切换即时生效，无需重启。
- 危险操作：停止会话/清除配对/退出登录必须二次确认 Dialog；token 用密码视觉 + 「显示」切换；长 URL 用 `maxLines=1 + Ellipsis` + 点击复制（Toast/Snackbar 反馈）。
- 可达性：每个 `SettingSwitchRow` 有 title+description；IconButton 有 contentDescription；分割小组件用 `Role.Switch`（Switch 自带）；服务器信息表用对齐的 `InfoRow`，值长时折行不裁剪。

### 7.6 空态 / 加载 / 错误（全屏一致）
- 每屏三态都要专门视觉：**加载**用 `LoadingIndicator`/skeleton（不要文字「加载中…」裸奔）；**空**用 `EmptyState(title, description, icon, actionLabel)`；**错误**用 `errorContainer` 卡 + 明确「重试」+ 建议。
- 错误分级：未配对 → 引导「去设置」；隧道/网络断 → 「重新连接」；ComfyUI 未就绪 → 「服务启动中」+ 自动轮询；生成失败（node_errors）→ 中文摘要 + 查看详情折叠。
- 空态可以放 1 个 `ShapeDecoration`（`AnimaShape.cookie/flower/sunny`，alpha ≤0.2）做视觉，但标题/操作必须是界面主元素；空态也要有出口（去生成 / 去设置 / 清除搜索），不能是死胡同。
- Snackbar 统一放在 Scaffold（有底部导航时避免被遮挡）；带 action 时用 `SnackbarDuration.Long`（约 4s）给出可操作时间。

## 8. QA 对照清单（按截图验收，24 项）

**布局 / 间距**
- [ ] 1. 全部间距来自 `Spacing`/8dp 网格，无 4/6/14/17 等随机 dp。
- [ ] 2. 392×873dp（1080×2400）下无溢出/裁剪，长英文 tag、长 URL、多行提示词都 `maxLines + Ellipsis`。
- [ ] 3. 页面左右边距 20dp、卡片内边距 20dp，底部有 ≥24dp 滚动余量。
- [ ] 4. 每屏只有一个大标题（headlineMediumEmphasized），层级 = 大标题 > 卡片标题 > 正文 > 元数据。
- [ ] 5. 键盘弹出时输入框可见（adjustResize/imePadding），滚动不被底部导航遮挡。

**颜色 / 主题**
- [ ] 6. 浅色与深色（跟随系统 + 强制）逐屏可读，正文对比度 ≥4.5:1、大字 ≥3:1。
- [ ] 7. 无硬编码 `Color(0x…)`（`Color.Transparent`/装饰 alpha 除外）；全部 `MaterialTheme.colorScheme.*` / `extended.*`。
- [ ] 8. 动态取色开/关两种情况下 primary/secondary/tertiary 均来自主题，色板预览一致。
- [ ] 9. 卡片 tonal 分层正确（background → surfaceContainerLow → surfaceContainerHigh），Hero 只用 1 处高饱和容器。
- [ ] 10. 状态色（`StatusPill`/StatusTone）与文字并存，不存在仅靠颜色传达状态的地方。

**字体 / 形状**
- [ ] 11. `*Emphasized` 只用于标题/关键按钮/关键数字，正文不用；中文 tracking 收敛（正文 0–0.1sp）。
- [ ] 12. `labelSmall` 11sp 只用于元数据，未用于可点击控件主文案。
- [ ] 13. 圆角来自 `AnimaShape`/`MaterialTheme.shapes`，无手写任意 dp；卡片 26dp、Hero 32dp、输入 18dp、按钮胶囊。
- [ ] 14. 装饰性 MaterialShapes 每屏 ≤2 处、仅装饰层、alpha ≤0.2；未用于功能语义。

**动效 / 交互**
- [ ] 15. 空间动画用 `AnimaMotion.spatial/spatialFast` 或 `motionScheme.*SpatialSpec`，颜色/透明度用 effects；没有游离的 `tween(300)`。
- [ ] 16. 单次动效 ≤500ms 等效，无循环装饰动画；展开/收起顺滑、无跳动。
- [ ] 17. 按压有反馈（scale/形状 morph），且不与 ButtonGroup 的按压膨胀叠加。
- [ ] 18. 触控目标 ≥48dp；IconButton 全部有 contentDescription，装饰图标为 null。
- [ ] 19. TalkBack 下浮动工具栏/FAB 菜单可操作（展开态、返回键可收起、可读状态）。

**组件 / 状态**
- [ ] 20. 进度：生成中 determinate wavy 有百分比与已用时长；排队/部署用不定态；中断有确认。
- [ ] 21. ButtonGroup 溢出菜单正常（可见项 ≤4，溢出项可点、与主题一致）。
- [ ] 22. 图库加载/空/错误三态齐全且都有出口；查看器 保存/分享/填回参数 三个动作均可用。
- [ ] 23. 未配对/连接中/就绪/失败四态在生成页与设置页文案一致，不出现英文异常裸串。
- [ ] 24. 没有使用 @Deprecated 组件（`SectionCard/ClickableCard/SectionHeader/ChipRow/StatusDot`、旧 `TextSecondary` 等），没有 `androidx.compose.material.*` 旧库（icons 除外）。
