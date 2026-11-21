## 改了什么

<!-- 一句话说明这个 PR 的目标；必要时列 bullet。 -->

## 为什么

<!-- 关联 Issue / 背景 / 用户场景。 -->
Closes #

## 怎么验证

- [ ] `scripts/build.sh` 成功
- [ ] `source scripts/env.sh && cd android && ./gradlew :app:testDebugUnitTest` 全绿
- [ ] 改了服务端 / 网关：`python3 colab/build_notebook.py`，生成物已同步
- [ ] 改了 TPU 版本：`python3 scripts/check_tpu_patches.py` 通过
- [ ] 改了 UI：已跑 `scripts/device_shots.sh --phase full --dim`，截图如下

验证步骤 / 命令：

```text
（粘贴关键输出或说明）
```

## 截图 / 录屏（UI 改动）

<!-- 直接拖图片进来；注明深色/浅色、T4/TPU 状态。 -->

## 检查清单

- [ ] 没有提交 `outputs/`、`.tmp/`、token、PAIR、keystore
- [ ] 改了接口：`docs/contract.md` 已同步
- [ ] 改了网关 / 部署 cell：notebook 与 Android assets 已同步
- [ ] 没有无关的大规模格式化 / 重命名
- [ ] 破坏性变更已在描述中写明
