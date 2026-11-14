# Anima Colab · 设计规范（Material 3 Expressive）

> 目标：按 Google 最新设计语言 **Material 3 Expressive（2026，含 Expresssive layout / 8dp spacing / Compose-first）**
> 重做交互与外观。深/浅色、动态取色（Material You）、弹性动效、形状库、表现力排版全部落地。
> 参考研究：`docs/design/m3-expressive-reference.md`（研究者整理）。

## 0. 硬性约束

- **只改自己负责的文件**（见文末「文件所有权」）。需要改基础组件/主题/ViewModel → 消息发给 Lead。
- **功能不能丢**：每个屏幕的现有能力、文案含义、状态机、按钮行为必须保留。
- **编译**：只通过 `scripts/team-compile.sh` 编译（内部有 flock 串行锁）。如果报错来自别人的文件，忽略；
  只要自己的文件没有错误即可，最终集成由 Lead 负责。
- **不要新增依赖 / 不要 run app / 不要 adb**。
- 颜色一律用 `MaterialTheme.colorScheme.*` 或 `MaterialTheme.extended.*`，禁止硬编码 `Color(0x…)`（装饰透明色除外）。
- 文案保持中文、简洁、面向普通用户；允许微调措辞，不要减功能。
- 手机目标尺寸 392dp × 873dp（1080×2400 @440dpi），所有布局必须能滚动、不溢出、不裁剪。

## 1. 设计方向

一句话：**「星光调色盘」——深夜紫的创作画布 + 会呼吸的 Expressive 组件。**

- **Color**：默认跟随系统深/浅色 + 品牌 expressive 色板（primary 电光紫 / secondary 蜜桃粉 / tertiary 青柠蓝）；可在设置中开 Android 12+ 动态取色（Material You）。
  用 M3 颜色角色分层：背景 `background` → 卡片 `surfaceContainerLow` → 互动控件 `surfaceContainerHigh` → 强调 `primary/primaryContainer`。
  成功/警告等语义色用 `MaterialTheme.extended.success / warning`。
- **Type**：M3 Expressive 默认字阶（含 Emphasized 变体）。大标题用 `headlineMediumEmphasized`，卡片标题用
  `titleMedium` / `titleSmall`，正文 `bodyMedium/bodySmall`，元数据 `labelSmall`；关键的按钮和标题用 `*Emphasized`。
- **Shape**：大圆角 + M3 shape library。卡片 26dp（`AnimaShape.card`），Hero 32dp（`hero`），小卡片 20dp，
  输入框 18dp，按钮胶囊（`pill`）。装饰性形状用 `ShapeDecoration` + `AnimaShape.cookie/sunny/flower/clover`。
- **Motion**：`MaterialExpressiveTheme` 已注入 `MotionScheme.expressive()`；局部动画用
  `AnimaMotion.spatial / spatialFast / springy` 或 `MaterialTheme.motionScheme.*`。
  展开/收起用 `animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>())`（注意 `AnimaMotion.*` 大多是 Float spec；尺寸类可直接用 `AnimaMotion.spatialSize`（IntSize），位移类用 motionScheme 对应类型），按压用 `Modifier.pressScale`，状态变化用 `AnimatedContent`。
  动效只服务于空间关系，不为了炫技；禁止长于 500ms 的动画。
- **Spacing**：8dp 网格。`Spacing.screen`(20) 屏幕左右边距，`card`(20) 卡片内边距，块间距 `lg`(16)/`xl`(20)/`xxl`(24)。

## 2. 令牌速查

```kotlin
// com.tom.animacolab.ui.theme
Spacing.xxs xs sm md lg xl xxl xxxl screen card
AnimaShape.hero card cardSmall field sheetTop pill full cookie softBurst sunny flower clover pillPolygon
AnimaMotion.spatial / spatialFast / springy / effects
MaterialTheme.extended.success / onSuccess / successContainer / warning / warningContainer …
StatusTone.Success / Warning / Error / Info / Neutral     // 通过 StatusPill / statusToneColors 使用
```

## 3. 共享组件 API（`ui/Components.kt`，Lead 维护，勿改）

```kotlin
ExpressiveCard(modifier, onClick = null, shape = AnimaShape.card,
   containerColor = colorScheme.surfaceContainerLow, contentPadding = PaddingValues(Spacing.card)) { /* ColumnScope */ }

ScreenHeader(title, subtitle = null, modifier = Modifier, leading = null,
   actions = {})   // 大标题 + 副标题 + 右侧操作；内部已含左右 20dp 边距

SectionLabel(text, modifier = Modifier, trailing = null)

StatusPill(text, tone: StatusTone, modifier = Modifier)

ChoiceChips(options: List<ChipOption>, selectedValue: String, onSelect: (String) -> Unit, modifier)
data class ChipOption(value: String, label: String, leadingIcon: ImageVector? = null)

SegmentedOptions(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier)

PrimaryActionButton(text, onClick, modifier = Modifier, enabled = true, loading = false, icon = null)
SecondaryActionButton(text, onClick, modifier = Modifier, enabled = true, icon = null)

SettingRow(title, modifier, description = null, leadingIcon = null, onClick = null, trailing = {})
SettingSwitchRow(title, checked, onCheckedChange, modifier, description = null, leadingIcon = null)

AnimaSliderRow(label, valueText, value, onValueChange, valueRange, modifier, steps = 0, enabled = true)

AnimaTextField(value, onValueChange, modifier, label = null, placeholder = null,
   minLines = 1, maxLines = 1, singleLine = false, enabled = true,
   keyboardOptions = KeyboardOptions.Default, visualTransformation = …,
   leadingIcon = null, trailingIcon = null)

DropdownField(label, value, options: List<String>, onSelect: (String) -> Unit, modifier)

EmptyState(title, modifier, description = null, icon = Icons.Outlined.AutoAwesome,
   actionLabel = null, onAction = null)

ShapeDecoration(modifier, tint = colorScheme.primary, shape = AnimaShape.cookie, rotation = 0f, alpha = 0.18f)

NetworkImage(vm, ref: ImageRef, modifier, targetPx = 1024, contentScale = ContentScale.Crop)

MetaText(text, modifier)          // 等宽小字，元数据
TagLine(tag)                      // 2 行截断说明
InfoRow(label, value, modifier, labelWidth = 88.dp)
Modifier.pressScale(interactionSource)     // 需要自己 remember MutableInteractionSource
AnimatedChevron(expanded, modifier)
```

**不要使用**（标记 @Deprecated，仅用于过渡期）：`SectionCard / ClickableCard / SectionHeader / ChipRow / StatusDot`。

图标：`androidx.compose.material.icons.outlined.*` 用于常规，`filled.*` 用于选中/强调；
`automirrored` 用于方向性图标。所有 IconButton 必须有 `contentDescription`。

## 4. 屏幕蓝图

### 4.1 生成（`GenerateScreen.kt`）

自上而下（可滚动 Column，底部留 24dp）：

1. `ScreenHeader(title = "Anima", subtitle = 状态行, actions = { StatusPill; 刷新 IconButton })`
   - 状态行优先级：部署 stage > 连接 Ready（`vm.serverSummary()` + 队列）> 连接中 > 连接异常 > 未配对。
2. 未配对（`ConnectionState.NotConfigured`）：
   - **Hero 卡**（`ExpressiveCard(shape = AnimaShape.hero)`，`primaryContainer` 背景 + 右侧 `ShapeDecoration`）：
     - 状态：未登录 → 说明 + “登录 Google” 主按钮 + “粘贴授权码完成登录” 文本按钮（打开授权码输入弹窗） + “手动配对（备用）”。
     - 已登录 → “一键启动 Colab T4” 主按钮 + 说明 + 手动配对入口。
     - Starting/Deploying → 当前 stage、`LinearWavyProgressIndicator()` 不定态、最近 3 行部署日志（等宽小字）、取消按钮。
     - Failed → 错误信息 + 重新启动 / 去设置。
3. 已配对：
   - 连接异常 → `ExpressiveCard` 错误提示 + 重试/一键重启按钮。
   - **模型卡**：`SectionLabel("模型")` + `ChoiceChips`（`p.id`/`p.display`）+ 当前模型 desc。
   - **提示词卡**（最重要，视觉上最突出）：正向 `AnimaTextField(minLines = 4, maxLines = 8)`、
     负向可折叠（默认收起，带 `AnimatedChevron`）、自动质量/自动负向开关（`SettingSwitchRow` 或紧凑开关行）、
     质量前缀说明（`MetaText`）、操作行：Tag/模板、存为模板、清空（图标按钮）。显示正文字符数。
   - **参数卡**：头部点击展开/收起（`animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>())` + `AnimatedChevron`），右侧 meta 摘要
     `30步 · CFG 4.5 · 1024²`。内容：步数滑杆、CFG 输入 + 张数 stepper、采样器/调度器下拉、尺寸 chips + 宽高编辑、
     种子输入 + 随机 + 固定开关、LoRA 下拉（有则显示）。
   - **生成主按钮**：`PrimaryActionButton(icon = Icons.Outlined.AutoAwesome, loading = active)`，未连接时 disabled 文案“连接后生成”。
   - **进度卡**（生成中）：状态标题 + `LinearWavyProgressIndicator(progress)`（Running）/不定态（Queued/Submitting）+
     已用时间 + “中断”按钮。
   - **失败卡**：错误信息 + 重试 / 关闭。
   - **成功卡**：大图（`AnimaShape.hero` 裁切 + 点击打开 viewer + `NetworkImage`）、元数据、操作行
     保存 / 分享 / 填回参数 / 图库。
4. 存为模板弹窗保留（`AlertDialog`，可用 `AnimaTextField` 输入名称）。

### 4.2 图库 + 大图查看（`GalleryScreen.kt` / `ImageViewer.kt`）

- `ScreenHeader("图库", subtitle = "${state.history.size} 张", actions = { 加载中 LoadingIndicator; 刷新 IconButton })`。
- 状态：未配对 → `EmptyState("还没连接 Colab", …, actionLabel = "去设置")`；加载中空列表 → 居中 `LoadingIndicator`；
  空列表 → `EmptyState("还没有生成记录", …, "去生成")`；
  有数据 → `LazyVerticalStaggeredGrid(2 列, spacing 12dp)`，卡片图片宽高比来自 `item.params`（缺省 1:1），
  多图角标、点击打开 viewer。卡片信息：步数/尺寸/seed + 时间，尽量克制。
- Viewer：全屏 `Dialog`，黑色/深色半透明背景；`HorizontalPager` 支持多图；图片 `ContentScale.Fit`；
  顶部浮动胶囊（关闭 + 时间 + 页码），用 surfaceContainer 半透明 + 边距 `statusBarsPadding()`；
  底部 `HorizontalFloatingToolbar(expanded = true)` 放 保存 / 分享 / 填回参数（图标 + 文案）；
  参数元数据用小号 text；关闭时调用 `vm.closeViewer()`；保留原有保存/分享/回填逻辑（先取当前页 ref）。
- 注意 Dialog 内需要自己处理 `statusBarsPadding()/navigationBarsPadding()`。

### 4.3 速查（`ExploreScreen.kt`）

- `ScreenHeader("速查", subtitle = "… 个 Tag · … 套模板")`。
- 模式切换：`SegmentedOptions(listOf("Tag 速查", "模板"), …)`（保留 Tag/模板两种模式）。
- Tag 模式：搜索框（`AnimaTextField(leadingIcon = Search)`）、插入位置 `SegmentedOptions(["正向","负向"])`
  或两个 ToggleButton、分类横向滚动 chip 行（用 `PrimaryScrollableTabRow` + `Tab` 或本地 LazyRow + FilterChip，
  颜色走主题令牌）、常用片段 `AssistChip` 行、Tag 列表（`LazyColumn`）：中文名 + 英文 `MetaText` + 分类标签 +
  artist 标记（secondary）+ 添加图标按钮；整行可点、按压缩放反馈。保留 `vm.appendTag(tag.en, positive)` /
  `vm.appendSnippet(...)`；列表最多 400 条不变。
- 模板模式：搜索框；`ExpressiveCard` 列表：名称、note、prompt 预览（3 行）、meta、应用/删除（用户模板）；
  空态 `EmptyState("没有模板")`。保留 `vm.applyTemplate/deleteTemplate`。

### 4.4 设置（`SettingsScreen.kt`）

`ScreenHeader("设置", subtitle = "外观 · 连接 · 数据")`，按 `SectionLabel` + `ExpressiveCard` 分组：

1. **Colab 一键启动**（Hero 卡，primaryContainer / 状态色装饰）：未登录 → 登录 Google 主按钮 + 粘贴授权码（弹窗输入）+ 说明；
   已登录 → 账号行（已登录 / 退出）+ 状态机（Starting/Deploying/Ready/Failed/Off）各自内容与按钮（启动/取消/重新连接/停止会话）；
   部署日志折叠展开保留。
2. **外观**（新）：`SegmentedOptions(["跟随系统","浅色","深色"], index = state.themeMode.ordinal,
   onSelect = { vm.setThemeMode(ThemeMode.entries[it]) })`；`SettingSwitchRow("动态取色（Material You)", state.dynamicColor,
   vm::setDynamicColor, description = "从壁纸提取颜色；Android 12+")`；一行色板预览（primary/secondary/tertiary 圆形色块 + 说明）。
3. **通知与保存**：自动下载、后台保活两个 `SettingSwitchRow`。
4. **手动配对（备用）**：可展开卡，地址/Token 输入（`AnimaTextField`，Token 用密码转换）+ 保存并连接、粘贴配对信息、
   重新连接、清除配对 + 当前连接状态文字。
5. **服务器**：`InfoRow` 设备/显存/队列/模型/LoRA/VAE/文本编码器/采样器 + 刷新按钮。
6. **怎么用**：折叠说明（一键模式/手动配对/注意事项），保留“导出备用笔记本（.ipynb）”。
7. **关于**：版本、数据说明、模型说明。

## 5. 视觉/交互质量清单（Lead 会按这个验收）

- [ ] 标题层级清晰：每屏只有一个大标题；卡片标题 > 正文 > 元数据。
- [ ] 每屏主操作 ≤ 1 个（PrimaryActionButton）；次要操作弱化为 Secondary/Text 按钮。
- [ ] 卡片使用 tonal 层（surfaceContainerLow/High），有 1 处 Hero/强调色块，不出现大面积高饱和色。
- [ ] 圆角/间距来自令牌，无随意 4/6/14dp 值；8dp 网格。
- [ ] 展开/收起、按压、状态切换有 spring 动效；无生硬跳变。
- [ ] 深色与浅色都可读（对比度 ≥ 4.5:1），无写死白/黑。
- [ ] 空/加载/错误状态都有专门视觉，不裸奔文字。
- [ ] 长文本、长 URL、长 tag 不溢出（maxLines + Ellipsis）。
- [ ] 触控目标 ≥ 48dp；IconButton 有 contentDescription；装饰图标为 null。
- [ ] 屏幕可滚动，键盘弹出（adjustResize）时输入框可见。
- [ ] 没有使用 @Deprecated 组件；没有 `androidx.compose.material.*` 旧库引用（icons 除外）。

## 6. 文件所有权

| 文件 | Owner | Task |
|---|---|---|
| `ui/theme/*.kt`、`ui/Components.kt`、`MainActivity.kt`、`data/Appearance.kt`、`data/ConfigStore.kt`、`AppViewModel.kt`、`res/**` | Lead | task-1 |
| `ui/GenerateScreen.kt` | generate-screen | task-3 |
| `ui/GalleryScreen.kt`、`ui/ImageViewer.kt` | gallery-viewer | task-4 |
| `ui/ExploreScreen.kt` | explore-screen | task-5 |
| `ui/SettingsScreen.kt` | settings-screen | task-6 |

`docs/design/m3-expressive-reference.md` 由 design-researcher 维护，可随时阅读。
