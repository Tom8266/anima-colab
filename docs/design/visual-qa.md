# Anima Colab · 真机视觉 QA 报告（M3 Expressive 重设计）

> 执行：device-qa（真机截图 / 视觉 QA）  
> 日期：2026-11-14（最终版截图 22:36–22:40 重拍；APK 22:24 构建，md5 `261d56ac3ee9a962b74cb8644d91e47c`）
> 设备：Xiaomi M2012K11AC · Android 17 / API 37 · 1080×2400 @440dpi（392×873dp）· adb `ef9ca6f9`
> 截图流水线：`scripts/device_shots.sh --install --phase full`（`scripts/mock_gateway.py` 假网关 + `adb reverse tcp:8091` + prefs 注入，不依赖真实 Colab 会话）
> 问题分档：**P0** 崩溃/ANR/功能不可用；**P1** 主流程可见缺陷（非阻塞）；**P2** 体验/一致性优化；**观察项** 非缺陷。

## 1. 结论摘要

| 项目 | 结果 |
|---|---|
| 崩溃 / ANR | **无**（本轮 22:17–22:41 `adb logcat -b events` 无 Anima 的 `am_anr`/`am_crash`，`-b crash` 为空；15 张交付截图 + 5 张 `--extras` 补充图全程无异常退出） |
| Insets（状态栏 / 底部导航） | **通过**：内容从状态栏（95px）下方开始，底部导航区无内容被遮挡/截断；滚动余量正常；Viewer 使用 `statusBarsPadding()/navigationBarsPadding()`，顶部胶囊在状态栏下方、底部工具栏系统手势区上方 |
| 对比度 | **通过**：实测正文/标题/按钮/浮层文字全部 ≥4.5:1（最低 4.55:1，见 §5）；错误态、Hero 卡、Viewer 浮层均在安全范围 |
| 裁切 / 溢出 | 无硬裁切；长提示词（150 字）、长模型名、长 URL 均有正确换行/截断；速查分类 Tab 行改为左右渐隐，无硬切（P2-5 已复验） |
| 深浅色 / 动态取色 | **通过**：品牌深色（01–08/11–14）、品牌浅色（09/10）、动态取色 Material You（15）三套主题均正常渲染，品牌色板与动态色板差异明显（证明开关生效） |
| Hero / 装饰形状 | 克制：未配对 Hero 保留低透明度形状；Settings 的 Colab Hero 已移除装饰形状，避免压到状态 pill / 退出登录（P2-3 已复验） |
| 问题统计 | **P0 = 0，P1 = 0，P2 = 0（全部修复；最终真机复验见 §11）**，观察项 4 |

**验收判定：可以发布。** P1「双进度」已在 `13-generate-running.png` 复验；P2-1 / P2-2 / P2-4 同轮修复；P2-3 / P2-5 / P2-6 及本轮新发现的细节问题（结果卡行尾「·」、存模板弹窗输入框、下拉菜单锚点、速查横屏）已在 22:36–22:40 的重拍与真机点检中复验，详见 §11。

## 2. 已修复项复验（均通过）

由 Lead 两轮修复，已在最终版截图（22:36–22:40）与真机交互上复验：

1. **Settings Hero 对比度统一 `onPrimaryContainer`** — 07/10 实测：Hero 标题 7.86 / 13.47:1，正文 5.23–7.58:1，`退出登录` 7.86 / 13.47:1，`一键启动 Colab T4` 按钮文字 8.25 / 6.88:1；无低对比文字。**PASS**
2. **ImageViewer 浮层与 scrim 区分度** — 04 顶部胶囊 / 元数据胶囊 / 底部工具栏容器（surfaceContainerHighest + outlineVariant 描边 + 阴影）与 90%+ 黑 scrim 区分明显；文字对比 6.85–10.11:1。**PASS**（与上一轮"浮层糊在背景里"相比已解决）
3. **错误卡层级** — 12 由大面积高饱和红改为 tonal 卡（surfaceContainerLow）+ error 图标/标题 + error 按钮，符合 DESIGN §5「不出现大面积高饱和色」；标题/正文对比 10.2–13.5:1，仅状态 pill 为小面积高饱和（合理）。**PASS**
4. **错误文案汉化** — 12 正文为「连不上 Colab 服务，请检查手机网络/代理后重试，或点「一键重启 Colab」重新申请会话」，不再暴露 `Failed to connect to…` 原始英文。**PASS**
5. **Gallery 元数据两行分组** — 03 每卡固定两行（步数·尺寸 / seed·时间），6 张图卡信息密度与可读性明显好于上一轮三行。**PASS**
6. **Explore 插入位置紧凑化 + 首屏密度** — 05「正向/负向」并入搜索行右侧，分类 Tab 行更紧凑，首屏可见 Tag 从 4 条提升到 7 条（+75%）。**PASS**
7. **参数区 2×2 尺寸 tile / 参数行对齐** — 02 CFG·张数、采样器·调度器、尺寸 2×2、（宽/高/应用）三列对齐，无错位。**PASS**
8. **Viewer 保存/分享不关闭** — 22:3x 真机点检：查看器内点「保存」后查看器保持打开，并弹出「已保存到相册（Pictures/AnimaColab）」snackbar；分享同样不关闭。**PASS**
9. **【原 P1】生成中「双进度」重复** — `GenerateScreen` 已按进度卡在视口内的实际位置判断常驻条（不是 `atBottom`）；`13-generate-running.png` 复验：进度卡完整显示且**不再出现第二条常驻进度**。**PASS**
10. **【原 P2-1】模型 ChoiceChips 折行** — 展示名缩短为 `Base v1.0` / `Aesthetic v1.1`，01/09/15 两个 chip 已同排单行，模型卡高度回收。**PASS**
11. **【原 P2-2】模板卡 meta 折行/行尾孤「·」** — 06 变为单行 `模型 Base v1.0 · 30 步 · CFG 4.5 · 832×1216`。**PASS**
12. **【原 P2-4】错误态 Header 副标题孤字** — 12 副标题缩短为 `连接异常，可刷新重试`，单行无孤字。**PASS**

## 3. P0 — 无

## 4. P1 — 已全部修复（0 条遗留）

### P1-1 生成中「双进度」重复显示，常驻进度条覆盖在进度卡之上 —— ✅ RESOLVED（补拍截图 13）

- **原现象**：同一屏同时出现页内「进度卡」（`正在生成` / `节点 采样` / `28/30 · 93%` / `中断`）和底部「常驻进度条」（`生成中 93%` / `已用 5s` / `✕`），两层 Surface 叠在一起、信息重复。
- **修复**：`GenerateScreen.kt` 先按 `atBottom` 隐藏常驻条，后进一步改为按进度卡在滚动内容中的实际位置判断（`ui/StickyProgress.kt`）——卡片完全移出视口才显示常驻条。
- **复验**（`13-generate-running.png`，19:54 补拍）：滚动到底后进度卡完整显示（`正在生成` / `节点 采样 21/30 · 70%` / 波浪进度条 / `已用 6s · 队列 1` / `中断`），**页面内不再出现第二条「生成中 XX%」进度**；反向路径（点生成后不滚动）只显示底部常驻条、进度卡在视口外，也无重复。两条主路径均无双进度。
- **残余边界**：原「小幅上滑停在中间」的过渡态已随位置判断修复（§6.4、§11）；13 复验无双进度。
- **定位 / Owner**：`ui/GenerateScreen.kt` — `showSticky` 与 `cardTopPx/cardHeightPx` 判定、常驻条挂载、`ui/StickyProgress.kt`。**owner: generate-screen**（已修复）

## 5. P2 — 体验与一致性优化（全部修复）


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

### P2-3 Hero 装饰形状与状态 pill 轻微相交 —— ✅ 已修复（§11：移除装饰形状）

- **截图**：`07-settings.png`（深色）、`10-light-settings.png`（浅色）
- **现象**：Hero 卡右上角 `未启动` 状态 pill 压在 `cookie` 装饰形状上；浅色主题下装饰形状更明显，pill 边缘与形状边界相切，视觉略碎。整体只有 2 个形状，尚未到「过乱」，但叠在状态控件上不够干净。
- **建议**：装饰形状整体下移/右移避开 pill 区域（如 `offset(x = 40.dp, y = 64.dp)`），或把 `alpha` 从 0.18/0.20 降到 0.10/0.12；保证装饰形状与任何前景控件不相交。
- **定位 / Owner**：`ui/SettingsScreen.kt` Hero 卡装饰 L190–215。**owner: settings-screen**
- **最终处理**：装饰形状整体移除。卡片右上角是状态 pill、下方是「退出登录」，56dp 形状无论怎么移都会压到前景控件；主色容器 + 火箭图标已足够表达 Hero（07/10 复验）。

### P2-4 错误态 Header 副标题折行后出现孤字「试」 —— ✅ 本轮已修复（12 补拍：副标题单行）

- **截图**：`12-error-generate.png`
- **现象**：Header 副标题 `连接异常，可点刷新重试` 折行后第二行只剩一个「试」字（孤字），占满一行高度、破坏标题层级。
- **建议**：缩短文案为 `连接异常，可刷新重试`（少 2 字可单行），或给 Header 文本列加宽/允许 `maxLines = 2` 时用 `TextOverflow.Ellipsis`；也可在 Error 态隐藏副标题（信息已在下方错误卡中完整给出）。
- **定位 / Owner**：`ui/GenerateScreen.kt` HeaderStatus 文案 L361。**owner: generate-screen**

### P2-5 速查分类 Tab 行右边缘硬裁切、无渐隐提示 —— ✅ 已修复 · 真机复验

- **截图**：`05-explore-tags.png`
- **现象**：分类 Tab 行（全部/头发/眼睛表情/服装…）在屏幕右边缘直接被切断（「服装」只露半字），没有 edge fade/箭头提示可横向滚动；首屏 Tab 可见性与可发现性一般。
- **建议**：使用 `PrimaryScrollableTabRow`/`LazyRow` 并加右侧 edge fade（或 `HorizontalFade`），或把「288 个」计数 chip 移到 Tab 行下方/标题行，给 Tab 更多横向空间（当前可见 3 个分类）。
- **定位 / Owner**：`ui/ExploreScreen.kt` 分类 Tab 行 + 计数 chip。**owner: explore-screen**
- **最终处理**：Tab 行左右各 20dp 渐隐到页面背景；05 复验无硬切。

### P2-6 空图库空态的装饰 blob 略碎 —— ✅ 已修复 · 真机复验

- **截图**：`11-empty-gallery.png`
- **现象**：空态插画由 3 个叠放装饰形状（cookie + sunny + flower）+ 中心图片图标组成，深色下较暗但形状边缘互相切割，略碎；且空态区域上下留白较大（约占屏幕 1/3+1/3）。
- **建议**：装饰形状减到 1–2 个（保留一个主 blob + 中心图标），并给空态内容加 `Spacing.xxxl` 顶部偏移做视觉重心（不必严格垂直居中）。
- **定位 / Owner**：`ui/GalleryScreen.kt` `GalleryEmptyState` L246 起。**owner: gallery-viewer**
- **最终处理**：`EmptyState` 统一减为 2 个形状（主 cookie + 右上 sunny），11 复验无互相切割。

> 另一处 Viewer 顶部胶囊在「单图无页码」时右侧留白偏大（左侧只有 X + 日期），建议胶囊宽度随内容自适应或补页码/标题位；`04-viewer.png`。**owner: gallery-viewer**（P2 可选，不单列条目）

## 6. 观察项（非缺陷，供参考）

1. **步数滑杆未选中段颜色**：M3 `Slider` 默认未选中轨为 `secondaryContainer`（品牌深色下 `#7A2A52`），与卡片背景对比约 **1.89:1**。这是 Material Expressive 默认视觉，看起来「有设计感」；若想满足更严格的非文本对比（≥3:1），可把 `AnimaSliderRow` 的 `trackColor` 覆盖为 `surfaceContainerHighest`（与进度条 track 一致）。非阻塞。
2. **Gallery 首屏密度**：6 张 mock 历史时首屏约 2.5 排；元数据已从 3 行压到 2 行，若还想再提密度可把网格 `spacedBy(12dp)` 收到 `8dp`（需与 Lead 的 12dp 规范确认）。
3. **动态取色对照有效**：15（dark + dynamic_color=true）呈现系统壁纸的青蓝 Material You 色板，01（dark + dynamic=false）为品牌电光紫，两者差异明显，证明默认品牌色 / 可开启动态取色的逻辑正确。
4. **常驻进度条过渡态（已解决）**：`showSticky` 已改为按进度卡在滚动内容中的实际位置判断（`ui/StickyProgress.kt`），只有卡片完全移出视口才显示常驻条；「小幅上滑停在中间」也不再同屏。13 复验无双进度。

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

## 8. 覆盖缺口（§11 已补验 / 仍缺）

已补验（`scripts/device_shots.sh --extras`，截图在 [`docs/screenshots/qa/`](../screenshots/qa/README.md)）：

- **键盘输入态**：`imePadding` 生效，内容区止于键盘上沿，提示词焦点行可见；底部导航被键盘顶起而不是盖住输入框（16）。
- **弹窗**：存为模板弹窗真机点检（17）；分割按钮下拉、清空/中断确认弹窗同轮目检，无遮挡或低对比。
- **字体放大 1.3**：生成页 / 设置页 / 速查页均无溢出、无裁切，Header 状态 pill 与副标题不重叠（18）。
- **横屏**：速查页在 <480dp 高度下切换 compact 布局（标题+模式同排、搜索/Tab 随列表滚动），横屏可见并滚动 Tag 列表（19/20）；生成/设置/图库本身整页可滚动。
- **Viewer 保存/分享不关闭**：真机点「保存」后查看器保持，显示保存结果 snackbar。

仍缺（需要真实环境 / 人工）：

- Colab 会话 Starting / Deploying / Ready / Failed 态 Hero 卡（需要真实 T4 会话，mock 只覆盖连接态）；
- 动态取色开启时的设置页截图（15 只拍了生成页）；
- 小屏（<360dp 宽）设备、系统手势导航/三键导航切换；
- 动效观感（仅静态截图，无录屏），以及真实生成耗时下的长进度/中断路径。

## 9. 复现方式与产物

```bash
source scripts/env.sh
scripts/device_shots.sh --install --phase full            # 构建 + 安装 + 15 张交付截图
scripts/device_shots.sh --install --phase full --extras   # 额外拍键盘/弹窗/字体/横屏 → docs/screenshots/qa/
# 最终 APK 已安装时：scripts/device_shots.sh --phase full --extras --dim
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
| `13-generate-running.png` | 生成中（进度卡；常驻条按进度卡可见性隐藏） | 品牌深色 |
| `14-generate-result.png` | 生成成功结果卡 | 品牌深色 |
| `15-dynamic-color.png` | 动态取色 Material You 对照 | 深色 + 动态色 |

## 10. 追补修复记录（2026-11-14 晚；已被 §11 最终复验覆盖）

> 21:45 做过一次 light + 动态取色的中间复验；下表状态以 22:36–22:40 最终 APK 的真机复验为准。

| 条目 | 状态 | 改动 |
|---|---|---|
| P2-3 Hero 装饰形状被状态 pill 压住 | ✅ 22:3x 复验 | `SettingsScreen.kt`：Hero 内的装饰形状整体移除（原先「只留 sunny」仍会压到「退出登录」）；主色容器 + 火箭图标已足够表达 Hero |
| P2-5 速查分类 Tab 硬裁切 | ✅ 22:3x 复验 | `ExploreScreen.kt` `CategoryTabRow`：Tab 行**左右**边缘各 20dp 渐隐到页面背景（宽度 = 行首/行尾 `edgePadding`，滚到两端时不压暗文字）；计数 chip 保留 |
| P2-6 空态装饰 blob 略碎 | ✅ 22:3x 复验 | `Components.kt` `EmptyState`：3 个装饰减为 2 个（主 cookie + 右上 sunny），边缘不再互相切割 |
| §6.4 常驻进度条残余过渡态 | ✅ 22:3x 复验 | `GenerateScreen.kt`：不再用 `atBottom`，按进度卡在滚动内容里的实际位置判断——卡片完全移出视口才显示常驻条，彻底消除「卡片可见 + 常驻条」同屏 |
| 观察项：Viewer 单图顶部胶囊右侧留白 | ✅ 22:3x 复验 | `ImageViewer.kt`：胶囊宽度随内容自适应（去掉 `fillMaxWidth`，标题 `weight(1f, fill = false)`），多图时页码仍靠右 |
| **新发现**：结果卡 meta 行尾孤「·」 | ✅ 22:3x 复验 | `GenerateScreen.kt` `SuccessCard`：参数与 seed 拆成两行 MetaText，分隔符不再落在换行点（14） |
| **新发现**：存为模板弹窗输入框「隐身」 | ✅ 22:3x 复验 | `GenerateScreen.kt` `SaveTemplateDialog`：AlertDialog 用 `surfaceContainerLow`，与 TextField 的 `surfaceContainerHigh` 拉开层级；打开自动聚焦（QA 17） |
| **新发现**：SplitButton 下拉菜单从整行左端弹出 | ✅ 22:3x 复验 | `GenerateScreen.kt`：`DropdownMenu` 移入展开按钮的 `Box`，菜单锚在触发按钮下方 |
| **新发现**：速查横屏列表被固定头部挤到只剩几像素 | ✅ 22:3x 复验 | `ExploreScreen.kt`：`BoxWithConstraints` 检测高度 <480dp，切 compact 头部，搜索/分类 Tab 进入 `LazyColumn`（QA 19/20） |
| 「已装 2 个」等宽字体字距散 | ✅ 22:3x 复验 | 模型数改用 `labelSmall`，不再走等宽 `MetaText`（01/09/15） |
| 截图脚本 full 少图不报错 | ✅ | `scripts/device_shots.sh`：11–15 加入 `REQUIRED`；新增 `--extras` 覆盖键盘/弹窗/字体/横屏 |

仍为观察项：

- §6.1 步数滑杆未选中轨对比度：M3 `Slider` 默认视觉，改成 `surfaceContainerHighest` 反而会让未填充段与卡片背景更接近，先不动。
- §6.2 图库网格间距 12dp：属于设计规范，等 Lead 决定是否收成 8dp。
- 动效观感 / 真实 Colab 会话态 / 动态取色设置页 / 小屏设备（见 §8、§11.3）。

## 11. 最终复验（2026-11-14 22:36–22:40）

> 最终 APK：`android/app/build/outputs/apk/debug/app-debug.apk`，22:24 构建，md5 `261d56ac3ee9a962b74cb8644d91e47c`。
> 15 张交付截图 + 5 张 QA 补充图均由 `scripts/device_shots.sh --phase full --extras --dim` 生成；
> 补充图在 [`docs/screenshots/qa/`](../screenshots/qa/README.md)，不混入交付截图集。

### 11.1 结果

| 项 | 结果 |
|---|---|
| P0 / P1 | 0 / 0（`13` 无双进度；无崩溃/ANR） |
| P2 | 0（P2-1/2/3/4/5/6 全部复验，含本轮新发现的 5 个细节项） |
| 15 张交付截图 | 全部重拍，主题/状态与 §9 清单一致 |
| 覆盖缺口 | 键盘态、弹窗、字体放大 1.3、横屏、Viewer 保存不关闭均已补验（§8） |
| 设备设置 | `--dim` 亮度、字体缩放、旋转在退出时恢复（脚本 `cleanup` 保护） |

### 11.2 新增/修改的关键点

1. **结果卡元数据两行**：14 第一行 `30步 · CFG 4.5 · er_sde · 1024×1024`，第二行 `seed 3664636396`，不再以孤立「·」结尾。
2. **存为模板弹窗**：输入框在弹窗内可见（QA 17），打开即聚焦并弹出键盘。
3. **SplitButton 菜单**：从展开按钮正下方弹出，不再贴在整行左侧。
4. **速查 compact 布局**：19 横屏首屏可见搜索/分类/片段；20 上滑后可见 Tag 列表；竖屏 05 布局不变。
5. **Settings Hero**：07/10 不再有装饰形状与 pill/「退出登录」相交。
6. **模型数**：01/09/15 的「已装 2 个」为常规字距。

### 11.3 仍存/仍缺

- 真实 Colab 会话的 Starting / Deploying / Ready / Failed Hero 态（需真实 T4 会话）。
- 动态取色开启时的设置页（15 只拍生成页；浅色/深色设置页已拍 07/10）。
- 动效观感与长任务中断路径（静态截图无法覆盖，需录屏/真实生成）。
- 小屏（<360dp 宽）与三键导航；本机只有 392dp / 手势导航。
