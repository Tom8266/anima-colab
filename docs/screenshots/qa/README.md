# 交互态 QA 补充截图

这组截图由 `scripts/device_shots.sh --phase full --extras` 生成（2026-11-14 22:39，
最终 APK md5 `261d56ac3ee9a962b74cb8644d91e47c`），用来覆盖 15 张交付截图拍不到的交互态。
与 `docs/screenshots/redesign/` 分开，避免把键盘/系统 UI 混进设计稿。

| 文件 | 内容 | 验证点 |
|---|---|---|
| `16-keyboard-generate.png` | 生成页 · 提示词聚焦 + 键盘弹出 | `imePadding`/`adjustResize` 生效，内容止于键盘上沿，焦点行可见，底部导航不被键盘遮挡 |
| `17-dialog-save-template.png` | 「存为模板」弹窗 | 输入框在 `surfaceContainerLow` 弹窗上可见；打开自动聚焦并弹出键盘 |
| `18-font-scale-1.3.png` | 生成页 · 系统字体 1.3× | 无溢出/裁切；Header 状态 pill 与副标题不重叠 |
| `19-landscape-explore.png` | 速查页 · 横屏（compact） | 标题/模式同排，搜索、分类 Tab、片段在列表首屏可见 |
| `20-landscape-explore-list.png` | 速查页 · 横屏上滑后 | 搜索/分类 Tab 随列表滚走，Tag 列表可达、可点 |

复现：

```bash
source scripts/env.sh
scripts/device_shots.sh --install --phase full --extras
# 或已安装最新 APK 时：
scripts/device_shots.sh --phase full --extras --dim
```

脚本会在退出时恢复面板亮度、字体缩放与屏幕旋转（`cleanup` trap）。
