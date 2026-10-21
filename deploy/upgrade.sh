#!/usr/bin/env bash
# SJHERP 升级：可选 git pull → mysqldump → 重建镜像启动（Flyway 随 app 启动自动迁移）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0
SKIP_BACKUP=0
SKIP_GIT=0

usage() {
  cat <<'EOF'
SJHERP 升级

用法：
  ./deploy/upgrade.sh                 拉取代码、备份 MySQL、重建并启动（Flyway 自动迁移）
  ./deploy/upgrade.sh --check         只校验，不拉代码、不备份、不启动
  ./deploy/upgrade.sh --skip-git      不执行 git pull，按当前工作区构建
  ./deploy/upgrade.sh --skip-backup   跳过升级前 mysqldump（不推荐）
  ./deploy/upgrade.sh --help

回滚边界（请先读完再升级）：
  - Flyway 只向前迁移，本脚本不能自动回退已执行的 Vxx SQL。
  - 安全回滚 = 用升级前的 SQL 备份走 ./deploy/restore.sh --mysql <文件> → 检出旧版本代码 → 再 ./deploy/upgrade.sh --skip-git --skip-backup
  - 若新版本没有新的 Flyway 脚本，仅回退镜像/代码通常可用。
  - 升级前默认只 dump MySQL；完整 MySQL+Qdrant 备份与演练见 docs/部署-备份恢复.md。
  - 不要使用 docker compose down -v，那会删掉数据卷。
EOF
}

for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --skip-git) SKIP_GIT=1 ;;
    --skip-backup) SKIP_BACKUP=1 ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$arg （--help 查看用法）" ;;
  esac
done

pull_code() {
  [[ "$SKIP_GIT" -eq 0 ]] || { info "跳过 git pull"; return 0; }
  if [[ ! -d "$REPO_ROOT/.git" ]]; then
    warn "当前目录不是 git 仓库，跳过 git pull（按现有文件构建）"
    return 0
  fi
  need_cmd git
  if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
    die "工作区有未提交改动，拒绝 git pull。请提交/stash，或加 --skip-git"
  fi
  info "拉取最新代码"
  git -C "$REPO_ROOT" pull --ff-only
}

backup_mysql() {
  [[ "$SKIP_BACKUP" -eq 0 ]] || { warn "已跳过 MySQL 备份"; return 0; }
  "$DEPLOY_DIR/backup.sh" --mysql-only --reason pre-upgrade --allow-skip-if-down --skip-prune
}

main() {
  [[ -f "$COMPOSE_FILE" ]] || die "缺少 $COMPOSE_FILE"
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    bash -n "$DEPLOY_DIR/upgrade.sh"
    bash -n "$DEPLOY_DIR/backup.sh"
    require_env_keys "$ENV_EXAMPLE"
    if command -v docker >/dev/null 2>&1; then
      compose config >/dev/null
    else
      warn "没有 docker，跳过 compose config"
    fi
    info "upgrade --check 通过"
    exit 0
  fi

  [[ -f "$ENV_FILE" ]] || die "找不到 deploy/.env。请先 ./deploy/install.sh"
  require_env_keys "$ENV_FILE"
  command -v docker >/dev/null 2>&1 || die "未安装 Docker"

  pull_code
  backup_mysql

  info "重建镜像并启动（app 启动时 Flyway 自动执行新迁移）"
  compose up -d --build
  wait_http "$(base_url)/api/health" 240
  "$DEPLOY_DIR/smoke.sh"
  info "升级完成。若页面异常，见 docs/部署-一键安装.md 回滚说明"
}

main
