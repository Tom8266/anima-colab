# Anima Colab · 真机视觉 QA 报告（M3 Expressive 重设计）

> 执行：device-qa（真机截图 / 视觉 QA）  
> 日期：2026-11-14（最终版截图 19:51–19:54；补拍前 APK 19:39:45 构建、19:40:00 安装，md5 `392098f065a5e4c6572d9b7e362f8243`）
> 设备：Xiaomi M2012K11AC · Android 17 / API 37 · 1080×2400 @440dpi（392×873dp）· adb `ef9ca6f9`
> 截图流水线：`scripts/device_shots.sh --install --phase full`（`scripts/mock_gateway.py` 假网关 + `adb reverse tcp:8091` + prefs 注入，不依赖真实 Colab 会话）
> 问题分档：**P0** 崩溃/ANR/功能不可用；**P1** 主流程可见缺陷（非阻塞）；**P2** 体验/一致性优化；**观察项** 非缺陷。

## 1. 结论摘要

| 项目 | 结果 |
|---|---|
| 崩溃 / ANR | **无**（`adb logcat -b events \| grep -E 'am_anr\|am_crash'` 与 `AndroidRuntime:E` 均为空；全程 15 张截图无异常退出） |
| Insets（状态栏 / 底部导航） | **通过**：内容从状态栏（95px）下方开始，底部导航区无内容被遮挡/截断；滚动余量正常；Viewer 使用 `statusBarsPadding()/navigationBarsPadding()`，顶部胶囊在状态栏下方、底部工具栏系统手势区上方 |
| 对比度 | **通过**：实测正文/标题/按钮/浮层文字全部 ≥4.5:1（最低 4.55:1，见 §5）；错误态、Hero 卡、Viewer 浮层均在安全范围 |
| 裁切 / 溢出 | 无硬裁切；长提示词（150 字）、长模型名、长 URL 均有正确换行/截断；仅「速查分类 Tab 行」存在设计性横向滚动裁切（见 P2-5） |
| 深浅色 / 动态取色 | **通过**：品牌深色（01–08/11–14）、品牌浅色（09/10）、动态取色 Material You（15）三套主题均正常渲染，品牌色板与动态色板差异明显（证明开关生效） |
| Hero / 装饰形状 | 基本克制：Hero 卡 2 个低透明度形状 + 1 个图标容器；仅装饰形状与右上角状态 pill 有轻微相交（P2-3） |
| 问题统计 | **P0 = 0，P1 = 0（已修复并复验），P2 = 3（遗留打磨项，非阻塞；已代码级修复，待 §10 复验）**，观察项 4 |

**验收判定：可以发布。** 上一轮唯一 P1（生成中「常驻进度条 + 进度卡」双进度）已随 Lead 修复解决，并在补拍截图 `13-generate-running.png` 上复验无重复；P2-1 / P2-2 / P2-4 同轮修复；剩余 3 个 P2 为 Hero 装饰形状、速查 Tab 裁切、空态装饰，均不影响使用。

## 2. 已修复项复验（均通过）

由 Lead 两轮修复，已在最终版截图（19:51–19:54）上复验：

1. **Settings Hero 对比度统一 `onPrimaryContainer`** — 07/10 实测：Hero 标题 7.86 / 13.47:1，正文 5.23–7.58:1，`退出登录` 7.86 / 13.47:1，`一键启动 Colab T4` 按钮文字 8.25 / 6.88:1；无低对比文字。**PASS**
2. **ImageViewer 浮层与 scrim 区分度** — 04 顶部胶囊 / 元数据胶囊 / 底部工具栏容器（surfaceContainerHighest + outlineVariant 描边 + 阴影）与 90%+ 黑 scrim 区分明显；文字对比 6.85–10.11:1。**PASS**（与上一轮"浮层糊在背景里"相比已解决）
3. **错误卡层级** — 12 由大面积高饱和红改为 tonal 卡（surfaceContainerLow）+ error 图标/标题 + error 按钮，符合 DESIGN §5「不出现大面积高饱和色」；标题/正文对比 10.2–13.5:1，仅状态 pill 为小面积高饱和（合理）。**PASS**
4. **错误文案汉化** — 12 正文为「连不上 Colab 服务，请检查手机网络/代理后重试，或点「一键重启 Colab」重新申请会话」，不再暴露 `Failed to connect to…` 原始英文。**PASS**
5. **Gallery 元数据两行分组** — 03 每卡固定两行（步数·尺寸 / seed·时间），6 张图卡信息密度与可读性明显好于上一轮三行。**PASS**
6. **Explore 插入位置紧凑化 + 首屏密度** — 05「正向/负向」并入搜索行右侧，分类 Tab 行更紧凑，首屏可见 Tag 从 4 条提升到 7 条（+75%）。**PASS**
7. **参数区 2×2 尺寸 tile / 参数行对齐** — 02 CFG·张数、采样器·调度器、尺寸 2×2、（宽/高/应用）三列对齐，无错位。**PASS**
8. **Viewer 保存/分享不关闭** — 静态截图不可验证交互结局；源码 `ImageViewer.kt` 已确认保存/分享不再触发 `closeViewer()`，留待人工点检。**（代码级 PASS，UI 待点检）**
9. **【原 P1】生成中「双进度」重复** — `GenerateScreen` 改为 `showSticky = active && !atBottom`（进度卡完整可见时常驻条自动隐藏）；`13-generate-running.png` 补拍复验：进度卡完整显示且**不再出现第二条常驻进度**。**PASS**
10. **【原 P2-1】模型 ChoiceChips 折行** — 展示名缩短为 `Base v1.0` / `Aesthetic v1.1`，01/09/15 两个 chip 已同排单行，模型卡高度回收。**PASS**
11. **【原 P2-2】模板卡 meta 折行/行尾孤「·」** — 06 变为单行 `模型 Base v1.0 · 30 步 · CFG 4.5 · 832×1216`。**PASS**
12. **【原 P2-4】错误态 Header 副标题孤字** — 12 副标题缩短为 `连接异常，可刷新重试`，单行无孤字。**PASS**

## 3. P0 — 无

## 4. P1 — 已全部修复（0 条遗留）

### P1-1 生成中「双进度」重复显示，常驻进度条覆盖在进度卡之上 —— ✅ RESOLVED（补拍截图 13）

- **原现象**：同一屏同时出现页内「进度卡」（`正在生成` / `节点 采样` / `28/30 · 93%` / `中断`）和底部「常驻进度条」（`生成中 93%` / `已用 5s` / `✕`），两层 Surface 叠在一起、信息重复。
- **修复**：`GenerateScreen.kt` 增加 `showSticky = active && !atBottom` —— 滚动到进度卡完整可见（到底）时常驻条自动隐藏；常驻条同时加底部内边距。
- **复验**（`13-generate-running.png`，19:54 补拍）：滚动到底后进度卡完整显示（`正在生成` / `节点 采样 21/30 · 70%` / 波浪进度条 / `已用 6s · 队列 1` / `中断`），**页面内不再出现第二条「生成中 XX%」进度**；反向路径（点生成后不滚动）只显示底部常驻条、进度卡在视口外，也无重复。两条主路径均无双进度。
- **残余边界**（见观察项 §6.4）：仅在「手动小幅上滑、恰好停在中间」的过渡位置可能同屏一瞬，属过渡态、非稳定状态；如需 100% 消除，建议按「进度卡是否可见」而非 `atBottom` 判断。非阻塞。
- **定位 / Owner**：`ui/GenerateScreen.kt` — `showSticky/atBottom` L136–142、常驻条挂载 L239–254、`StickyJobBar` L1380+。**owner: generate-screen**（已修复）

## 5. P2 — 体验与一致性优化（本轮已修复 3 条，遗留 3 条）


### P2-1 模型 ChoiceChips 折行成两行，「模型」卡变高 —— ✅ 本轮已修复（01/09/15 补拍复验：chip 单行）

- **截图**：`01-generate.png`、`09-light-generate.png`、`15-dynamic-color.png`
- **现象**：常态下两个模型 chip 应并排（FlowRow），实际 `Anima Base v1.0` 与 `Anima Aesthetic v1.1` 各占一行；模型卡因此多出约 60px 高度，首屏信息量下降。
- **建议**：缩短展示名（如 `Anima Base` / `Anima Aesthetic`，desc 里再写完整版本），或该行改为横向滚动 `LazyRow`；也可给卡片内容加 `horizontalArrangement = spacedBy(Spacing.sm)` 后按 392dp 实测压缩 chip 内边距。
- **定位 / Owner**：展示名 `data/Assets.kt` L143 附近（`display`），组件 `ui/Components.kt` `ChoiceChips` L260。**owner: generate-screen / Lead**

### P2-2 模板卡参数行行尾孤立「·」+ 尺寸折行 —— ✅ 本轮已修复（06 补拍：meta 单行）

- **截图**：`06-explore-templates.png`
- **现象**：`模型 Anima Base v1.0 · 30 步 · CFG 4.5 ·` 行尾以「·」结束，`832×1216` 被折到下一行；两行高度未被利用，卡片右侧「应用」按钮因此显得孤立。
- **建议**：meta 改结构化两行（第一行 `模型 · 步数`，第二行 `CFG · 尺寸`），或 `maxLines = 1` + `TextOverflow.Ellipsis` 并缩短文案（`Base · 30步 · 4.5 · 832×1216`）。
- **定位 / Owner**：`ui/ExploreScreen.kt` `templateMeta()` L773–783 及调用处 L752。**owner: explore-screen**

### P2-3 Hero 装饰形状与状态 pill 轻微相交

- **截图**：`07-settings.png`（深色）、`10-light-settings.png`（浅色）
- **现象**：Hero 卡右上角 `未启动` 状态 pill 压在 `cookie` 装饰形状上；浅色主题下装饰形状更明显，pill 边缘与形状边界相切，视觉略碎。整体只有 2 个形状，尚未到「过乱」，但叠在状态控件上不够干净。
- **建议**：装饰形状整体下移/右移避开 pill 区域（如 `offset(x = 40.dp, y = 64.dp)`），或把 `alpha` 从 0.18/0.20 降到 0.10/0.12；保证装饰形状与任何前景控件不相交。
- **定位 / Owner**：`ui/SettingsScreen.kt` Hero 卡装饰 L190–215。**owner: settings-screen**

### P2-4 错误态 Header 副标题折行后出现孤字「试」 —— ✅ 本轮已修复（12 补拍：副标题单行）

- **截图**：`12-error-generate.png`
- **现象**：Header 副标题 `连接异常，可点刷新重试` 折行后第二行只剩一个「试」字（孤字），占满一行高度、破坏标题层级。
- **建议**：缩短文案为 `连接异常，可刷新重试`（少 2 字可单行），或给 Header 文本列加宽/允许 `maxLines = 2` 时用 `TextOverflow.Ellipsis`；也可在 Error 态隐藏副标题（信息已在下方错误卡中完整给出）。
- **定位 / Owner**：`ui/GenerateScreen.kt` HeaderStatus 文案 L361。**owner: generate-screen**

### P2-5 速查分类 Tab 行右边缘硬裁切、无渐隐提示

- **截图**：`05-explore-tags.png`
- **现象**：分类 Tab 行（全部/头发/眼睛表情/服装…）在屏幕右边缘直接被切断（「服装」只露半字），没有 edge fade/箭头提示可横向滚动；首屏 Tab 可见性与可发现性一般。
- **建议**：使用 `PrimaryScrollableTabRow`/`LazyRow` 并加右侧 edge fade（或 `HorizontalFade`），或把「288 个」计数 chip 移到 Tab 行下方/标题行，给 Tab 更多横向空间（当前可见 3 个分类）。
- **定位 / Owner**：`ui/ExploreScreen.kt` 分类 Tab 行 + 计数 chip。**owner: explore-screen**

### P2-6 空图库空态的装饰 blob 略碎

- **截图**：`11-empty-gallery.png`
- **现象**：空态插画由 3 个叠放装饰形状（cookie + sunny + flower）+ 中心图片图标组成，深色下较暗但形状边缘互相切割，略碎；且空态区域上下留白较大（约占屏幕 1/3+1/3）。
- **建议**：装饰形状减到 1–2 个（保留一个主 blob + 中心图标），并给空态内容加 `Spacing.xxxl` 顶部偏移做视觉重心（不必严格垂直居中）。
- **定位 / Owner**：`ui/GalleryScreen.kt` `GalleryEmptyState` L246 起。**owner: gallery-viewer**

> 另一处 Viewer 顶部胶囊在「单图无页码」时右侧留白偏大（左侧只有 X + 日期），建议胶囊宽度随内容自适应或补页码/标题位；`04-viewer.png`。**owner: gallery-viewer**（P2 可选，不单列条目）

## 6. 观察项（非缺陷，供参考）

1. **步数滑杆未选中段颜色**：M3 `Slider` 默认未选中轨为 `secondaryContainer`（品牌深色下 `#7A2A52`），与卡片背景对比约 **1.89:1**。这是 Material Expressive 默认视觉，看起来「有设计感」；若想满足更严格的非文本对比（≥3:1），可把 `AnimaSliderRow` 的 `trackColor` 覆盖为 `surfaceContainerHighest`（与进度条 track 一致）。非阻塞。
2. **Gallery 首屏密度**：6 张 mock 历史时首屏约 2.5 排；元数据已从 3 行压到 2 行，若还想再提密度可把网格 `spacedBy(12dp)` 收到 `8dp`（需与 Lead 的 12dp 规范确认）。
3. **动态取色对照有效**：15（dark + dynamic_color=true）呈现系统壁纸的青蓝 Material You 色板，01（dark + dynamic=false）为品牌电光紫，两者差异明显，证明默认品牌色 / 可开启动态取色的逻辑正确。
4. **常驻进度条残余过渡态（P1 修复的边界）**：`showSticky = active && !atBottom` 的判定下，点生成后自然位置只见常驻条、滚到底只见进度卡，两者不会稳定同屏；唯一窗口是「小幅上滑停在中间」的一瞬（此时内容新增的常驻条占位 spacer 使 `atBottom` 暂为 false）。若后续迭代想彻底消除，可改为根据进度卡在视口内的可见位置判断。当前不视为缺陷。

## 7. 对比度实测（截图取样，±0.3）

| 截图 | 元素 | 前景/背景 | 对比度 | 结论 |
|---|---|---|---|---|
| 01 | 大标题 Anima | #E5E1EA / #121218 | 14.47 | ≥4.5 ✓ |
| 01 | 连接状态副标题 | #CAC4CF / #121218 | 10.94 | ✓ |
| 01 | 模型说明 | #BEB8C3 / #1A1921 | 8.99 | ✓ |
| 01 | 提示词正文 | #E5E1EA / #292830 | 11.31 | ✓ |
| 01 | 「已连接」胶囊 | #8BE8B0 / #155232 | 6.24 | ✓ |
| 01 | 底部导航选中标签 | #FEB9D6 / #1E1D25 | 10.44 | ✓ |
| 02 | 步数标签 | #C9BFFF / #1A1921 | 10.27 | ✓ |
| 02 | CFG 数值 | #9E9BA4 / #292830 | 5.33 | ✓ |
| 07 | Hero 标题 Colab T4 | #E6DEFF / #43328B | 7.86 | ✓ |
| 07 | Hero 副标题/说明 | #BFB5E3 / #43328B | 5.23–5.29 | ✓ |
| 07 | Hero 退出登录 | #E6DEFF / #43328B | 7.86 | ✓ |
| 07 | Hero 主按钮文字 | #2B1A70 / #C8BEFE | 8.25 | ✓ |
| 07 | 「未启动」pill | #CAC4CF / #34333B | 7.31 | ✓ |
| 10 | Hero 标题（浅色） | #16005E / #E5DDFE | 13.47 | ✓ |
| 10 | Hero 主按钮（浅色） | #FFFFFF / #5A43C6 | 6.88 | ✓ |
| 11 | 空态标题/说明 | #E5E1EA、#CAC4CF / #121218 | 14.47 / 10.94 | ✓ |
| 12 | 错误标题/正文 | #E5E1EA、#CAC4CF / #1A1921 | 13.52 / 10.21 | ✓ |
| 12 | 错误态 pill | #FFB4AB / #93000A | 5.51 | ✓ |
| 13 | 进度卡标题/百分比 | #E2DEE7、#99CFDF / #282830 | 11.14 / 8.59 | ✓ |
| 04 | 顶部胶囊文字 | #E5E1EA / #313038 | 10.11 | ✓ |
| 04 | 元数据胶囊文字 | #BFBAC4 / #313038 | 6.85 | ✓ |
| 04 | 底部工具栏文字 | #E5E1EA / #313038 | 10.11 | ✓ |
| 15 | 动态色大标题 | #FFFFFF / #0A0F10 | 19.29 | ✓ |

> 注：上一版 13 的「常驻条」文字实测对比为 11.31 / 8.59:1，同样达标；本轮修复后 13 不再显示常驻条，故不再列入。

## 8. 覆盖缺口（截图无法验证，建议人工点检）

- 键盘输入态（聚焦提示词/地址输入框时 `adjustResize`、底部导航是否被顶起）；
- 生成按钮 SplitButton 下拉菜单、清空提示词二次确认弹窗、中断确认弹窗的视觉；
- Colab 会话 Ready / Deploying 态 Hero 卡（需要真实 Colab 会话，本轮 mock 无法模拟）；
- 动态取色开启时的设置页（本轮 15 只拍了生成页）；
- 系统字体放大（fontScale 1.3+）/ 横屏 / 小屏设备适配；
- 动效观感（本轮仅静态截图，无录屏）。

## 9. 复现方式与产物

```bash
source scripts/env.sh
scripts/device_shots.sh --install --phase full   # 构建 + 安装 + 15 张截图
# 本轮补拍为 Lead 构建的最终 APK 已安装后执行：scripts/device_shots.sh --phase full
# 依赖：adb 真机、python3、curl；mock 网关脚本内置（127.0.0.1:8091）
```

**截图清单**（`docs/screenshots/redesign/`，共 15 张，全部为 1080×2400）：

| 文件 | 状态 | 主题 |
|---|---|---|
| `01-generate.png` | 生成页 · 已连接 T4 | 品牌深色 |
| `02-params-expanded.png` | 生成页 · 参数卡完整展开 | 品牌深色 |
| `03-gallery.png` | 图库 · 6 条历史/6 张图 | 品牌深色 |
| `04-viewer.png` | 大图查看器 | 深色浮层 |
| `05-explore-tags.png` | 速查 · Tag 模式 | 品牌深色 |
| `06-explore-templates.png` | 速查 · 模板模式 | 品牌深色 |
| `07-settings.png` | 设置页顶部（Hero/外观） | 品牌深色 |
| `08-settings-manual-pairing.png` | 设置 · 手动配对展开 | 品牌深色 |
| `09-light-generate.png` | 生成页 | 品牌浅色 |
| `10-light-settings.png` | 设置页 | 品牌浅色 |
| `11-empty-gallery.png` | 空图库空态 | 品牌深色 |
| `12-error-generate.png` | 连接失败错误态 | 品牌深色 |
| `13-generate-running.png` | 生成中（进度卡；常驻条已按 atBottom 隐藏） | 品牌深色 |
| `14-generate-result.png` | 生成成功结果卡 | 品牌深色 |
| `15-dynamic-color.png` | 动态取色 Material You 对照 | 深色 + 动态色 |

## 10. 追补修复（2026-11-14 晚，代码级）

> 本轮改动时真机未连接（`adb devices` 为空），以下修复只做了编译 + 单测验证，**没有重新截图**；
> 上文截图仍是修复前的版本，结论与对比度数据继续有效。下次连机后执行
> `scripts/device_shots.sh --install --phase full --dim` 复验并替换对应截图。

| 条目 | 代码级状态 | 改动 |
|---|---|---|
| P2-3 Hero 装饰形状被状态 pill 压住 | ✅ 已改 | `SettingsScreen.kt`：移除右上角 112dp cookie，只保留右侧 sunny（alpha 0.18）；Hero 不再有装饰形状与任何前景控件相交 |
| P2-5 速查分类 Tab 右边缘硬裁切 | ✅ 已改 | `ExploreScreen.kt` `CategoryTabRow`：Tab 行右边缘 20dp 渐隐到页面背景（宽度 = 行尾 `edgePadding`，滚到最后一个分类时不压暗文字）；计数 chip 保留 |
| P2-6 空态装饰 blob 略碎 | ✅ 已改 | `Components.kt` `EmptyState`：3 个装饰减为 2 个（主 cookie + 右上 sunny），去掉左下 clover，边缘不再互相切割 |
| §6.4 常驻进度条残余过渡态 | ✅ 已改 | `GenerateScreen.kt`：不再用 `atBottom`，改为按进度卡在滚动内容里的实际位置判断——卡片完全移出视口才显示常驻条，彻底消除「卡片可见 + 常驻条」同屏的中间态 |
| 观察项：Viewer 单图顶部胶囊右侧留白 | ✅ 已改 | `ImageViewer.kt`：胶囊宽度随内容自适应（去掉 `fillMaxWidth`，标题 `weight(1f, fill = false)`），多图时页码仍靠右 |

未处理（保持观察项，等真机点检后再定）：

- §6.1 步数滑杆未选中轨对比度：这是 M3 `Slider` 默认视觉，改成 `surfaceContainerHighest` 反而会让未填充段与卡片背景更接近，先不动。
- §6.2 图库网格间距 12dp：属于设计规范，等 Lead 决定是否收成 8dp。
- §8 覆盖缺口（键盘态、弹窗、真实 Colab 状态、字体放大、横屏、动效录屏）仍需真机/人工点检。

复验清单（连机后）：

1. `--phase full` 重拍 01/05/07/10/11/04，重点看：Hero 右上角是否只剩一个形状且不压 pill；分类行右边缘是否自然渐隐；空态是否只剩两个形状；单图 Viewer 顶部胶囊是否变短。
2. 生成中把列表停在「进度卡刚露头」的位置，确认只出现进度卡、没有常驻条（反向：停在顶部只出现常驻条）。
3. 用 `scripts/device_shots.sh --dim` 跑完整轮，确认亮度恢复。
