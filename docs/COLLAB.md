# 协作方案（两人 / GitHub Flow）

这个仓库已经有 [CONTRIBUTING.md](../CONTRIBUTING.md)（怎么写代码）和 [AGENTS.md](../AGENTS.md)
（项目约定）。本文只补**两个人怎么配合**：分工、看板、每日节奏、GitHub 侧配置、环境一致性。

一句话原则：**`main` 永远可发布；一个任务一个分支一个 PR 一个 owner；改动接口先改契约。**

## 0. 速查

| 场景 | 命令 |
|---|---|
| 环境自检 | `scripts/collab/doctor.sh` |
| 开任务分支 | `scripts/collab/new-task.sh feat 图库批量保存` |
| 同步主干 | `git fetch origin && git rebase origin/main` |
| 推送 + 开 PR | `git push -u origin HEAD && gh pr create --fill` |
| 看别人的 PR | `gh pr status` |
| 本地跑 CI 同款检查 | `scripts/build.sh && source scripts/env.sh && cd android && ./gradlew :app:testDebugUnitTest` |

## 1. 角色与分工

两个人权限对等，都能改任何文件；但**每个任务只有一个 owner**（PR 作者），owner 对结果负责。

- **按功能切，不按目录切**。不要采用"你只碰 `android/`、我只碰 `colab/`"的分法——一个功能
  经常同时改 App 和网关，按目录切会互相等待。
- **同一时刻两人不要动同一块核心文件**：`android/.../ui/GenerateScreen.kt`、
  `AppViewModel.kt`、`colab/gateway.py`。要动就先在 Issue 里说一声。
- **契约先行**：`docs/contract.md` 是 App ⇄ 网关 ⇄ ComfyUI 的唯一依据。任何影响接口的改动，
  先提一个只改 contract 的 PR，合了再各自改两端。
- **生成物归 owner**：改 `colab/gateway.py` 的人负责跑 `python3 colab/build_notebook.py` 并提交
  4 个生成物（CI 会检查）。
- **发版人是 @Tom8266**（唯一），另一人负责发版前真机验收。见 [RELEASING.md](../RELEASING.md)。
- **分歧处理**：接口 / 架构分歧在 Issue 或 Draft PR 里对齐；48 小时没结论，默认选"不动契约、
  向后兼容"的方案先推进。

## 2. Issue、标签与看板

**每个 PR 都必须关联 Issue**（PR 模板里的 `Closes #`）。任务再小也开 Issue——这样看板上的
进展才是真的，不用靠问。

看板（GitHub Projects，建议名 `Anima Colab`）五列：

| 列 | 含义 |
|---|---|
| Backlog | 想到的、还没排期 |
| This month | 本月 Milestone 内的任务 |
| Doing | 已开分支、正在做（一个最多 2 项） |
| In review | PR 已开、等对方看 |
| Done | 已合并 |

标签用 `setup-github.sh` 一次建好：

| 前缀 | 取值 | 用途 |
|---|---|---|
| `type:` | `feat` `fix` `docs` `refactor` `chore` | 和分支前缀一一对应 |
| `area:` | `android` `server` `docs` `ci` | 粗粒度模块，看板可按此筛 |
| `priority:` | `p0` `p1` `p2` | `p0` = 阻断发布/线上不可用 |
| `status:` | `needs-review` `blocked` `good first issue` | 流动性标记 |

Milestone 按版本建（`v0.2.0`），**每个月最多一个**，和发版节奏一致。

## 3. 每天怎么走

1. **开始**：`gh pr status` 看有没有等你的 review；看板看自己 Doing 的卡片。
2. **同步**：`git switch main && git pull --ff-only`（昨天可能有合并）。
3. **做**：开分支 → 小步提交（`feat:` / `fix:` …）。
4. **推**：`git push -u origin HEAD`，然后开 PR。**CI 没绿之前开成 Draft**。
5. **互评**：对方的 PR **工作日 24 小时内**至少给出一次明确意见。
6. **合**：Squash and merge，合并后删分支（仓库设置里可自动删）。
7. **收尾**：切回 `main` 拉一次，确认本地和远端一致。

Review 约定：

- 阻塞性问题用 **Request changes**；风格建议写成 `nit:` 的普通 comment，**不阻塞合并**。
- 作者不能批准自己的 PR。合并前最后一个 push 之后需要重新批准（已配在保护规则里）。
- 评论里可以直接提改动建议，作者点 Commit suggestion 即可。

**异步日站会（可选但推荐）**：每天在当天 Issue 里各留一句"昨天做完 X / 今天做 Y / 卡在 Z"，
不用约时间开会。

## 4. 冲突预防

- 分支寿命 **≤ 2 天**；超过就拆小，或先合一个能跑的版本。
- 每天开工前 `git rebase origin/main`，不要攒着冲突。
- 大改（>500 行 / 动 `contract.md`）先开 Draft PR 让对面看一眼方向。
- 只对自己的分支 `git push --force-with-lease`，**`main` 永远不 force push**（保护规则已禁止）。
- 合并顺序：先合小的、独立的；两个 PR 都改同一文件时，后合的人负责 rebase 并重跑 CI。

## 5. 环境一致性

CI 用什么，本地就用什么，避免"我这儿能过"：

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **21**（CI: temurin 21） | 低于 17 直接编不过；26 不建议（AGP/Gradle 未验证） |
| Android SDK | `platforms;android-37.0`、`build-tools;37.0.0` | `compileSdk = 37` |
| Python | CI 3.12 / 本地 3.13 均可 | 只跑 `py_compile`、生成脚本、单测 |
| Gradle | 9.9.0，**只走 `./gradlew`** | 不要用 IDE 自带的 Gradle 版本 |
| 构建入口 | `scripts/build.sh` | 会带上 `scripts/env.sh` 的 SDK/JDK 配置 |

- `scripts/collab/doctor.sh` 全绿才算环境就绪；它会把缺的东西直接列出来。
- Windows 上脚本按 **Git Bash** 写，不要在 PowerShell 里跑 `.sh`。
- 网络不稳时优先用 **SSH remote**（`git@github.com:Tom8266/anima-colab.git`），比 HTTPS 稳。
- 国内访问 `dl.google.com` 往往只有几十 KB/s：在 `.gradle-user-home/init.d/cn-mirrors.gradle`
  挂一份镜像（本地文件，不提交），首次构建会快很多。
- 报 `journal-1.lock 拒绝访问` 说明 IDE 的 Gradle 守护进程占着全局缓存锁；用
  `scripts/build.sh`（会优先用项目内 `.gradle-user-home`）或临时关掉 IDE 的守护进程。
- `android/local.properties` 是本机的（gitignore），不要提交。

## 6. GitHub 侧一次性配置（仓库管理员）

在仓库根目录跑一次即可（需要 `gh auth login` 且对仓库有 admin 权限）：

```bash
scripts/collab/setup-github.sh --collaborator <同事的GitHub用户名>
```

它做四件事，可重复执行（幂等）：

| 项 | 设置 |
|---|---|
| 协作者 | 邀请为 `write`（可直接推分支、开 PR，不能改仓库设置） |
| 合并方式 | 只允许 **Squash**，自动删 head 分支，允许 auto-merge |
| `main` 保护 | 必须 PR、1 个批准、新 push 后重新批准、必须解决对话、必须过 `server-check` + `android-test`、分支需最新、禁止 force push / 删除、规则对管理员同样生效 |
| 标签 | 第 2 节的 `type:` / `area:` / `priority:` / `status:` |

看板（Projects）不提供 API 一键创建，手动建一次：

> 仓库 → Projects → New project → Board → 按第 2 节建五列 → 把 Milestone `v0.x.0` 的 Issue 拉进来。

## 7. 新同事 30 分钟上手

1. 接受 GitHub 邀请，用 **SSH key** 配好认证（`ssh -T git@github.com` 能看到自己的用户名）。
2. 装 JDK 21、Android SDK（platform 37 / build-tools 37）、Python 3.12+、adb。
3. `git clone git@github.com:Tom8266/anima-colab.git && cd anima-colab`
4. `scripts/collab/doctor.sh` —— 全部 PASS。
5. `source scripts/env.sh && cd android && ./gradlew :app:testDebugUnitTest` —— 全绿（首次会下依赖，比较慢）。
6. 读四份文档：本文 → [CONTRIBUTING.md](../CONTRIBUTING.md) → [AGENTS.md](../AGENTS.md) → [docs/contract.md](contract.md)。
7. 领一个 `good first issue`，走一遍 Issue → 分支 → PR → Review → Squash merge。

## 8. 公开仓库的注意事项

仓库是公开的：不要提交 token、PAIR、隧道凭据、Google refresh token、keystore；截图和日志里
出现设备序列号、隧道地址要打码。`.gitignore` 已挡掉常见产物，但 `git add -A` 前仍要 `git status` 看一眼。

## FAQ

**Q: CI 的 `server-check` 报"生成物与模板不一致"？**
本地跑 `python3 colab/build_notebook.py`，把 4 个生成物一起提交。

**Q: 能不能直接推 `main`？**
不能。`main` 有保护规则，本地 pre-push 钩子也会拦。走 PR。

**Q: 两个人同时要发版怎么办？**
一个月只有一个正式版。谁发版由 @Tom8266 定，另一个人的改动进下个月。

**Q: 需要 `dev` 分支吗？**
不需要。`main` 就是集成分支，短分支 + PR 足够；只有发版前要冻结时才临时切 `release/vX.Y.Z`。
