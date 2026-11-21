# 发布流程（Release）

约定：**正式版本最多每自然月发布一次**。没有值得发布的内容就跳过，不为了发版而发版。
安全 / 严重回归可以例外，由维护者决定并记录原因。

## 分支模型

```text
main                已发布 / 稳定，受保护
  ↑ release PR（每月最多一次）
dev                 下一个版本的集成分支，受保护
  ↑ feature PR
feat/xxx            功能分支
release/vX.Y.Z      发版临时分支（发完自动删除）
```

- 日常功能从 `dev` 开分支，PR 合回 `dev`
- 每月从 `dev` 切 `release/vX.Y.Z`，PR 合到 `main`
- 合并后打 tag、构建 APK、发 GitHub Release
- `main` 只接受 release / 紧急 hotfix

## 1. 发布前检查

- [ ] `dev` 上 CI 全绿
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

版本号改动先通过 PR 合到 `dev`，再从 `dev` 切 release 分支。

## 3. 切 release 分支

```bash
git switch dev
git pull --ff-only
git switch -c release/v0.2.0
git push -u origin release/v0.2.0
```

在 GitHub 开 PR：`release/v0.2.0` → `main`

- 标题：`release: v0.2.0`
- 描述里贴发布摘要 / 验证结果
- CI 通过 + 1 人 Approve 后 **Squash and merge**

> 用临时 `release/...` 分支而不是直接 `dev → main`，是为了让“自动删除 head branch”
> 只删临时分支，不会动到 `dev`。

## 4. 打 tag

在 `main` 的最新提交上：

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

## 7. 发布后同步

把 `main` 的发布提交同步回 `dev`，避免下个版本落后：

```bash
git switch dev
git pull --ff-only
git merge main
git push
```

> `dev` 也受保护；维护者可用管理员权限直接 push 这一步，或者开一个
> `chore/sync-main-into-dev` PR 让另一个人 Approve。普通协作者请走 PR。

## 8. 收尾

- [ ] Release 页面已发布，APK 可下载，SHA-256 一致
- [ ] `main` / `dev` 已同步
- [ ] 相关 Issue / Milestone 关闭
- [ ] 下个版本的 Issue 已建好
- [ ] 本月不再发第二个正式版（hotfix 例外）
