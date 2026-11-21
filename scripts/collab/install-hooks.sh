#!/usr/bin/env bash
# 安装本地钩子 + 一套适合本项目的 git 默认行为。
# 每个开发者各跑一次：scripts/collab/install-hooks.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "不在 git 仓库里" >&2
    exit 1
fi

HOOK_DIR="$(git rev-parse --git-path hooks)"
mkdir -p "$HOOK_DIR"
cp scripts/collab/hooks/pre-push "$HOOK_DIR/pre-push"
chmod +x "$HOOK_DIR/pre-push"
echo "✓ pre-push 钩子已安装（禁止直推 main，放行变量 ALLOW_MAIN_PUSH=1）"

# 只影响本仓库
git config pull.rebase true
git config rebase.autoStash true
git config fetch.prune true
git config push.default current
git config branch.autoSetupRebase always
echo "✓ 已设置：pull.rebase / rebase.autoStash / fetch.prune / push.default=current"

echo
echo "当前身份：$(git config user.name) <$(git config user.email)>"
echo "如果这个邮箱不是你的 GitHub 账号邮箱，提交不会关联到你的头像："
echo "  git config user.email \"<你的 GitHub 邮箱 或 12345678+用户名@users.noreply.github.com>\""
