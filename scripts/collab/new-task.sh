#!/usr/bin/env bash
# 从最新 main 开一个任务分支。
# 用法：scripts/collab/new-task.sh <feat|fix|docs|refactor|chore> <短描述>
# 例：  scripts/collab/new-task.sh feat gallery-batch-save
set -euo pipefail

TYPE="${1:-}"
shift || true
DESC="${*:-}"

case "$TYPE" in
    feat|fix|docs|refactor|chore) ;;
    *)
        echo "用法：scripts/collab/new-task.sh <feat|fix|docs|refactor|chore> <短描述>" >&2
        echo "  分支名用英文短横线：feat/gallery-batch-save" >&2
        exit 2
        ;;
esac

# 生成 slug：小写、空格/下划线转横线、只留 a-z0-9-
SLUG="$(printf '%s' "$DESC" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -e 's/[[:space:]_]\+/-/g' -e 's/[^a-z0-9-]//g' -e 's/-\{2,\}/-/g' -e 's/^-//' -e 's/-$//')"
if [ -z "$SLUG" ]; then
    SLUG="task-$(date +%m%d)"
    echo "提示：描述里没有英文字母，分支名用 $SLUG；想用可读名字就补个英文 slug。" >&2
fi

BRANCH="$TYPE/$SLUG"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [ -n "$(git status --porcelain)" ]; then
    echo "工作区有未提交改动，先 commit 或 stash：" >&2
    git status --short >&2
    exit 1
fi

echo "→ 同步 main ..."
git switch main >/dev/null
git fetch origin --prune --quiet
git pull --ff-only --quiet

echo "→ 创建分支 $BRANCH"
git switch -c "$BRANCH"

cat <<EOF

分支已就绪：$BRANCH

下一步：
  git add -p && git commit -m "feat: 一句话说明"
  git push -u origin HEAD
  gh pr create --fill --base main      # 或者到网页开 PR

别忘了在 Issue 里把它标成 Doing，PR 描述里写 Closes #<issue>。
EOF
