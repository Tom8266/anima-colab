# 重设计真机截图（Material 3 Expressive）

全部为真机截图（Xiaomi M2012K11AC · Android 17 / API 37 · 1080×2400 @440dpi，即 392×873dp）。
2026-11-14 22:36–22:40 用最终 APK（md5 `261d56ac3ee9a962b74cb8644d91e47c`）重拍。
数据来自本地 mock 网关（`scripts/mock_gateway.py`），不依赖真实 Colab 会话；可一键复现：

```bash
scripts/device_shots.sh --install --phase full            # 15 张交付截图
scripts/device_shots.sh --install --phase full --extras   # 另拍键盘/弹窗/字体/横屏 → docs/screenshots/qa/
```

## 截图清单

| 文件 | 内容 | 主题 |
|---|---|---|
| `01-generate.png` | 生成页（已连接 T4，品牌色板） | 深色 + 品牌色 |
| `02-params-expanded.png` | 参数卡展开（滑杆/CFG/张数/采样器/尺寸 2×2/种子/LoRA） | 深色 + 品牌色 |
| `03-gallery.png` | 图库双列错落网格（多图角标、两行元数据） | 深色 + 品牌色 |
| `04-viewer.png` | 全屏查看器（Pager + 顶部胶囊 + 底部浮动工具栏） | 深色 + 品牌色 |
| `05-explore-tags.png` | 速查 Tag 模式（搜索 + 正/负向 + 分类 Tab + 288 个 Tag） | 深色 + 品牌色 |
| `06-explore-templates.png` | 速查模板模式（模板卡 + 应用） | 深色 + 品牌色 |
| `07-settings.png` | 设置页（连接 Hero + 外观 + 通知保存） | 深色 + 品牌色 |
| `08-settings-manual-pairing.png` | 设置-手动配对展开（地址/Token/服务器信息） | 深色 + 品牌色 |
| `09-light-generate.png` | 生成页 | 浅色 + 品牌色 |
| `10-light-settings.png` | 设置页（主题模式/动态取色开关/品牌色板） | 浅色 + 品牌色 |
| `11-empty-gallery.png` | 空图库空态（装饰形状 + 去生成 CTA） | 深色 + 品牌色 |
| `12-error-generate.png` | 连接失败错误态（中文可执行建议 + 重试） | 深色 + 品牌色 |
| `13-generate-running.png` | 生成中（波浪进度 / 百分比 / 已用时长 / 中断；无双进度） | 深色 + 品牌色 |
| `14-generate-result.png` | 生成完成结果卡（大图 + 保存/分享/填回/图库） | 深色 + 品牌色 |
| `15-dynamic-color.png` | **Material You 动态取色**（与 01 对照，证明壁纸取色生效） | 深色 + 动态取色 |

## 设计验收

- 视觉规范：[`docs/DESIGN.md`](../../DESIGN.md)
- Google 最新标准研究 + 24 项 QA 清单：[`docs/design/m3-expressive-reference.md`](../../design/m3-expressive-reference.md)
- 真机视觉 QA 报告（P0/P1/P2、对比度实测、覆盖缺口）：[`docs/design/visual-qa.md`](../../design/visual-qa.md)

> 旧版 UI 截图保留在 `docs/screenshots/legacy/`，仅作历史对照。

> 2026-11-14 最终复验已完成：P2-3 / P2-5 / P2-6、结果卡 meta 换行、存为模板弹窗、
> SplitButton 菜单锚点、速查横屏 compact 布局均已修复并重拍。交互态补充图（键盘、弹窗、
> 字体放大 1.3、横屏）在 [`docs/screenshots/qa/`](../qa/README.md)，修复明细见
> [`docs/design/visual-qa.md` §11](../../design/visual-qa.md)。
