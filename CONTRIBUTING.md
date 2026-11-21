# 参与开发（Contributing）

欢迎一起完善 Anima Colab。这个项目是 **Android App + Colab 服务端** 两部分，推荐两个人按
[GitHub Flow](https://docs.github.com/get-started/using-github/github-flow) 协作：

- `main` 始终可运行，**不要直接 push**
- 每个任务开一个短分支，通过 Pull Request（PR）合并
- 合并前至少 1 人 Review + CI 通过
- 发布由维护者打 tag、附 APK

## 1. 本地环境

```bash
git clone https://github.com/Tom8266/anima-colab.git
cd anima-colab

# Android：需要 JDK 17+、Android SDK（compileSdk 37）和 adb
scripts/build.sh
scripts/install.sh

# 单测
source scripts/env.sh && cd android && ./gradlew :app:testDebugUnitTest
```

没有真机也能开发大部分 UI / 服务端逻辑；视觉验收可用 mock 网关 + 自动截图：

```bash
scripts/device_shots.sh --install --phase full --dim
```

## 2. 分支与 PR

```bash
git switch main
git pull --ff-only
git switch -c feat/short-description   # 或 fix/、docs/、refactor/
```

分支命名：

| 前缀 | 用途 |
|---|---|
| `feat/` | 新功能 |
| `fix/` | 修 bug |
| `docs/` | 文档 / 截图 |
| `refactor/` | 重构，不改变行为 |
| `chore/` | 构建、依赖、工具 |

改完后：

```bash
git add -p
git commit -m "feat: 一句话说明"
git push -u origin feat/short-description
```

到 GitHub 开 PR，填写 PR 模板，等 CI 和 Review。合并方式统一用 **Squash and merge**。

提交信息建议：

```
feat: 图库支持批量保存
fix: TPU 模式首次启动不再覆盖 token
docs: 补充 Windows 构建说明
```

## 3. 提交前检查

- [ ] `scripts/build.sh` 成功
- [ ] `source scripts/env.sh && cd android && ./gradlew :app:testDebugUnitTest` 全绿
- [ ] 改 UI：跑 `scripts/device_shots.sh --phase full --dim`，截图放进 PR
- [ ] 改 `gateway.py` / 部署 cell：`python3 colab/build_notebook.py`，并确认 assets 同步
- [ ] 改 TPU 固定 ComfyUI 版本：`python3 scripts/check_tpu_patches.py` 通过
- [ ] 没有提交 `outputs/`、`.tmp/`、`android/app/build/`、token、PAIR、keystore

## 4. 这个项目的特殊约定

- **接口契约**：[`docs/contract.md`](docs/contract.md) 是 App ⇄ 网关 ⇄ ComfyUI 的唯一依据。
  改 API、字段、错误码，先改 contract，再改两端。
- **生成物**：`colab/gateway.py` 会被嵌入 notebook / deploy 脚本。
  改网关后必须跑 `python3 colab/build_notebook.py`；它同时同步
  `colab/deploy_script.py`、`colab/anima_colab.ipynb` 和 Android assets。
- **TPU**：TPU 模式固定 ComfyUI tag（当前 `v0.46.2`），依赖 11 处
  `comfy/model_management.py` 补丁。升级版本前先跑 `scripts/check_tpu_patches.py`。
- **UI**：优先复用 `ui/Components.kt` 与 `ui/theme/Tokens.kt`，颜色走 `colorScheme`；
  中文文案面向普通用户。
- **安全**：仓库是公开的。不要在代码、文档、截图、日志里放 token、PAIR、隧道地址凭据、
  Google refresh token、keystore。App 的 OAuth client 是公开 installed-app client，可以保留。
- **发布**：只由维护者操作。改 `versionCode` / `versionName`，打 `v0.x.y` tag，
  用 `gh release create` 附 debug APK 和 SHA-256。

## 5. Review 约定

- 一个 PR 只做一件事，尽量小；大改先开 Issue 或 Draft PR 讨论
- 作者不能自己批准自己的 PR；对方至少检查一次
- Review 关注：行为是否正确、是否有测试/验证、接口是否同步、有没有泄露凭据
- 风格问题不阻塞合并；正确性、数据安全、兼容性优先
- 合并后删除分支，并同步本地 `main`

## 6. 遇到冲突

```bash
git fetch origin
git rebase origin/main
# 解决冲突
git add <文件>
git rebase --continue
git push --force-with-lease
```

只对自己的功能分支使用 `--force-with-lease`，**不要 force push `main`**。

## 7. 问题与讨论

- 需求 / bug：开 Issue，写清环境（T4 / TPU、手机型号、App 版本）和复现步骤
- 设计 / 接口：在 Issue 或 PR 里先对齐，再动手
- 紧急问题：在 PR 里 @ 对方，或直接沟通

## 8. 许可证

提交代码即表示同意以本项目的 [MIT License](LICENSE) 发布。
