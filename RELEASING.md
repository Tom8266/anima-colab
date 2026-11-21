# 发布流程（Release）

约定：**正式版本最多每自然月发布一次**。没有值得发布的内容就跳过，不为了发版而发版。
安全 / 严重回归可以例外，由维护者决定并记录原因。

## 分支模型

```text
main                唯一长期分支，CI 始终绿；日常功能 PR 直接合到这里
  ↑ feature PR
feat/xxx            1-2 天的短分支

main @ v0.2.0       每月最多打一个 tag，构建 APK + GitHub Release
```

- 平时不需要 `dev` 分支；`main` 可以比最新 Release 新
- 用户下载的是 Release 里的 APK，不是 `main` 分支
- 只有发版前需要“冻结”时，才临时切 `release/vX.Y.Z`（可选，见第 3 节）

## 1. 发布前检查

- [ ] `main` 上 CI 全绿
- [ ] 真机跑过一轮：T4 一键启动 + 生成一张图；有条件再跑 TPU
- [ ] `docs/contract.md` 与实际接口一致
- [ ] `python3 colab/build_notebook.py` 后没有生成物 diff
- [ ] 没有未合并的破坏性改动
- [ ] 确定版本号（例如 `0.2.0`）

## 2. 版本号

在 `android/app/build.gradle.kts` 修改：

```kotlin
versionCode = 2          // 每次 release +1
versionName = "0.2.0"
```

单独开一个 PR（或作为最后一个功能 PR 的一部分）合到 `main`，确保版本号提交在 `main` 上。

## 3. 可选：需要冻结时切 release 分支

大多数月份可以跳过这一步，直接在 `main` 上打 tag。

如果发版前需要暂停新功能、只让紧急修复进入本版：

```bash
git switch main
git pull --ff-only
git switch -c release/v0.2.0
git push -u origin release/v0.2.0
```

- 只往 `release/v0.2.0` 放紧急修复（从 `release/v0.2.0` 开 `fix/*`，PR 回它）
- 发版前把 `release/v0.2.0` PR 到 `main`，Squash merge
- 发完删除该分支（或让 GitHub 自动删除）

## 4. 打 tag

在 `main` 的最新发布提交上：

```bash
git switch main
git pull --ff-only
git tag -a v0.2.0 -m "Anima Colab v0.2.0"
git push origin v0.2.0
```

tag 必须指向 `main` 上的发布提交。

## 5. 构建 APK

```bash
scripts/build.sh
mkdir -p outputs
cp android/app/build/outputs/apk/debug/app-debug.apk outputs/AnimaColab-0.2.0-debug.apk
sha256sum outputs/AnimaColab-0.2.0-debug.apk
```

> 当前发布的是 debug 签名测试包。以后要做正式签名，再补 keystore / CI 签名流程；
> keystore 绝不能进仓库。

## 6. 创建 GitHub Release

```bash
gh release create v0.2.0 outputs/AnimaColab-0.2.0-debug.apk \
  --target main \
  --title "Anima Colab v0.2.0（debug）" \
  --notes-file .tmp/release-notes.md
```

Release notes 至少包含：

- 本次新增 / 修复
- 下载文件 + SHA-256
- 已知限制（T4 / TPU、免费 Colab 限制）
- 升级 / 安装说明

## 7. 收尾

- [ ] Release 页面已发布，APK 可下载，SHA-256 一致
- [ ] 相关 Issue / Milestone 关闭
- [ ] 下个版本的 Issue 已建好
- [ ] 本月不再发第二个正式版（hotfix 例外）

> 如果使用了 `release/vX.Y.Z` 分支，确认它已合并到 `main` 并删除。
