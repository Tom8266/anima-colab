#!/usr/bin/env bash
# 一次性配置 GitHub 侧协作基建（需要 gh 已登录，且对仓库有 admin 权限）。
#
#   scripts/collab/setup-github.sh --collaborator <用户名> [--dry-run]
#
# 幂等：可以重复跑。
set -euo pipefail

COLLABORATOR=""
DRY_RUN=0
while [ $# -gt 0 ]; do
    case "$1" in
        --collaborator) COLLABORATOR="${2:-}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help)
            sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "未知参数：$1" >&2; exit 2 ;;
    esac
done

run() {
    if [ "$DRY_RUN" = "1" ]; then
        printf '  [dry-run] %s\n' "$*"
    else
        "$@"
    fi
}

command -v gh >/dev/null 2>&1 || { echo "没有 gh，先装 GitHub CLI" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh 未登录：先跑 gh auth login" >&2; exit 1; }

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo "仓库：$REPO"

ADMIN="$(gh api "repos/$REPO" --jq '.permissions.admin // false')"
if [ "$ADMIN" = "true" ]; then
    echo "权限：admin ✓"
elif [ "$DRY_RUN" = "1" ]; then
    echo "权限：未知（dry-run）"
else
    echo "权限：write（不是 admin）"
    echo "  → 标签仍会创建；仓库设置 / main 保护 / 协作者邀请需要仓库所有者执行，稍后跳过。"
fi

need_admin() { [ "$ADMIN" = "true" ]; }

# ---------- 1. 合并策略 ----------
echo
if need_admin || [ "$DRY_RUN" = "1" ]; then
    echo "→ 合并策略：只允许 Squash，自动删分支，允许 auto-merge"
    run gh api -X PATCH "repos/$REPO" \
        -F allow_squash_merge=true \
        -F allow_merge_commit=false \
        -F allow_rebase_merge=false \
        -F delete_branch_on_merge=true \
        -F allow_auto_merge=true \
        -f squash_merge_commit_title=PR_TITLE \
        -f squash_merge_commit_message=PR_BODY \
        >/dev/null
    echo "  ✓"
else
    echo "→ 跳过合并策略（需要 admin）"
fi

# ---------- 2. 标签 ----------
echo
echo "→ 标签"
LABELS=$(cat <<'EOF'
type: feat|0E8A16|新功能
type: fix|D73A4A|修 bug
type: docs|0075CA|文档 / 截图
type: refactor|5319E7|重构，不改变行为
type: chore|C5DEF5|构建、依赖、工具
area: android|1D76DB|App 端
area: server|006B75|colab / 网关 / notebook
area: docs|BFD4F2|docs/ 目录
area: ci|FBCA04|GitHub Actions / 脚本
priority: p0|B60205|阻断发布 / 不可用
priority: p1|D93F0B|本迭代要做
priority: p2|FEF2C0|有空再说
status: needs-review|F9D0C4|等 review
status: blocked|000000|被卡住
good first issue|7057FF|适合第一次上手
EOF
)
while IFS='|' read -r name color desc; do
    [ -z "$name" ] && continue
    run gh label create "$name" --color "$color" --description "$desc" --force >/dev/null
done <<< "$LABELS"
echo "  ✓ 已创建/更新 $(printf '%s\n' "$LABELS" | grep -c '|') 个标签"

# ---------- 3. main 分支保护 ----------
echo
if need_admin || [ "$DRY_RUN" = "1" ]; then
    echo "→ main 分支保护（PR + 1 批准 + CI 必过 + 禁止 force push）"
    if [ "$DRY_RUN" = "1" ]; then
        echo "  [dry-run] PUT repos/$REPO/branches/main/protection"
    else
        gh api -X PUT "repos/$REPO/branches/main/protection" --input - >/dev/null <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["server-check", "android-test"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 1,
    "require_last_push_approval": true
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON
        echo "  ✓ 已生效：$(gh api "repos/$REPO/branches/main/protection" \
            --jq '"PR=\(.required_pull_request_reviews.required_approving_review_count) checks=\(.required_status_checks.contexts|join(",")) force=\(.allow_force_pushes.enabled) admins=\(.enforce_admins.enabled)"')"
    fi
else
    echo "→ 跳过 main 保护（需要 admin）：请仓库所有者执行"
    echo "     gh auth switch --user <所有者> && scripts/collab/setup-github.sh"
fi

# ---------- 4. 协作者 ----------
echo
if [ -z "$COLLABORATOR" ]; then
    echo "→ 跳过协作者邀请（没给 --collaborator <用户名>）"
elif need_admin || [ "$DRY_RUN" = "1" ]; then
    echo "→ 邀请协作者 @$COLLABORATOR（write）"
    run gh api -X PUT "repos/$REPO/collaborators/$COLLABORATOR" -f permission=push >/dev/null
    echo "  ✓ 已发送邀请，对方在 GitHub 通知 / 邮件里接受后生效"
else
    echo "→ 跳过协作者邀请（需要 admin）：请仓库所有者邀请 @$COLLABORATOR"
fi

echo
echo "完成。还剩一件手动的事："
echo "  仓库 → Projects → New project（Board），按 docs/COLLAB.md 第 2 节建五列看板。"
