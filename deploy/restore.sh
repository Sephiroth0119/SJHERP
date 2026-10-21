#!/usr/bin/env bash
# SJHERP 恢复：用备份覆盖当前数据卷中的数据，不删除 named volume。
# 禁止把 docker compose down -v 当作恢复步骤。
# 文档：docs/部署-备份恢复.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0
ASSUME_YES=0
MYSQL_SPEC=""
QDRANT_SPEC=""

usage() {
  cat <<'EOF'
SJHERP 恢复（覆盖现有库，保留 docker volume）

用法：
  ./deploy/restore.sh --mysql latest
  ./deploy/restore.sh --mysql deploy/backups/mysql/sjherp-daily-XXXX.sql.gz
  ./deploy/restore.sh --mysql latest --qdrant latest
  ./deploy/restore.sh --mysql latest --yes          跳过交互确认（CI / 演练）
  ./deploy/restore.sh --check
  ./deploy/restore.sh --help

行为：
  1. 停 nginx / app（MySQL 继续跑）
  2. 把 SQL 备份灌回当前 mysql 数据卷
  3. 若指定 --qdrant：在同一个 qdrant 数据卷里清空 storage 文件后用 --storage-snapshot 恢复
  4. 按正常 compose 配置拉起服务并等待 /api/health

不会执行 docker compose down -v。误删 volume 不是本脚本的恢复路径。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    --help|-h) usage; exit 0 ;;
    --mysql)
      shift
      MYSQL_SPEC="${1:-}"
      [[ -n "$MYSQL_SPEC" ]] || die "--mysql 需要备份文件路径或 latest"
      ;;
    --mysql=*)
      MYSQL_SPEC="${1#--mysql=}"
      ;;
    --qdrant)
      shift
      QDRANT_SPEC="${1:-}"
      [[ -n "$QDRANT_SPEC" ]] || die "--qdrant 需要快照路径或 latest"
      ;;
    --qdrant=*)
      QDRANT_SPEC="${1#--qdrant=}"
      ;;
    -v|--volumes)
      die "拒绝 $1。恢复不要带 volume 删除。请读 docs/部署-备份恢复.md"
      ;;
    *)
      die "未知参数：$1 （--help 查看用法）"
      ;;
  esac
  shift
done

resolve_backup_file() {
  local spec="$1"
  local latest="$2"
  local extra_dir="$3"
  local candidate
  if [[ "$spec" == "latest" ]]; then
    candidate="$latest"
    [[ -e "$candidate" ]] || die "没有 latest 备份：$candidate 。请先 ./deploy/backup.sh"
    printf '%s' "$candidate"
    return 0
  fi
  for candidate in "$spec" "$BACKUP_MYSQL_DIR/$spec" "$BACKUP_QDRANT_DIR/$spec" "$BACKUP_DIR/$spec" "$extra_dir/$spec"; do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  die "找不到备份文件：$spec"
}

confirm_restore() {
  if [[ "$ASSUME_YES" -eq 1 ]]; then
    return 0
  fi
  if [[ ! -t 0 ]]; then
    die "非交互环境请加 --yes。恢复会覆盖当前 MySQL（以及可选的 Qdrant）数据"
  fi
  cat <<EOF
警告：即将用备份覆盖当前运行中的数据（财务真源在 MySQL）。
这不是 docker compose down -v。
MySQL 备份：${MYSQL_FILE:-（无）}
Qdrant 快照：${QDRANT_FILE:-（无）}
EOF
  printf '输入 YES 继续：'
  local ans
  read -r ans
  [[ "$ans" == "YES" ]] || die "已取消"
}

restore_mysql() {
  local file="$1"
  local password
  password="$(mysql_root_password)"
  [[ -s "$file" ]] || die "MySQL 备份为空：$file"
  if [[ "$file" == *.gz ]]; then
    gzip -t "$file"
  fi
  info "恢复 MySQL ← $file"
  if [[ "$file" == *.gz ]]; then
    gunzip -c "$file" | compose exec -T mysql mysql -uroot "-p${password}"
  else
    compose exec -T mysql mysql -uroot "-p${password}" < "$file"
  fi
  info "MySQL 已灌回（数据仍在原 named volume 中）"
}

wipe_qdrant_storage() {
  info "清空 Qdrant storage 文件（保留 named volume，不是 down -v）"
  compose run --rm -T --no-deps --user root --entrypoint sh qdrant -c \
    'if [ -d /qdrant/storage ]; then find /qdrant/storage -mindepth 1 -maxdepth 1 -exec rm -rf {} +; fi'
}

restore_qdrant() {
  local file="$1"
  local override
  [[ -s "$file" ]] || die "Qdrant 快照为空：$file"
  backup_dirs_init
  info "恢复 Qdrant ← $file"
  compose stop qdrant
  sleep 2
  wipe_qdrant_storage
  cp -f "$file" "$BACKUP_QDRANT_LIVE_DIR/restore.snapshot"
  override="$(mktemp)"
  cat > "$override" <<'YAML'
services:
  qdrant:
    command: ["./qdrant", "--storage-snapshot", "/qdrant/snapshots/restore.snapshot"]
YAML
  compose -f "$override" up -d qdrant
  wait_qdrant_ready 120
  info "快照已灌入 volume，按正常配置重启 Qdrant"
  compose up -d qdrant
  wait_qdrant_ready 90
  rm -f "$BACKUP_QDRANT_LIVE_DIR/restore.snapshot" "$override"
}

main() {
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    bash -n "$DEPLOY_DIR/restore.sh"
    backup_dirs_init
    info "restore --check 通过"
    exit 0
  fi
  [[ -n "$MYSQL_SPEC" ]] || die "必须指定 --mysql <文件|latest>。只恢复 Qdrant 不能救回财务数据"
  [[ -f "$ENV_FILE" ]] || die "找不到 deploy/.env。请先 ./deploy/install.sh"
  require_env_keys "$ENV_FILE"
  load_env_file "$ENV_FILE"
  command -v docker >/dev/null 2>&1 || die "未安装 Docker"
  command -v flock >/dev/null 2>&1 || die "缺少 flock（util-linux）"
  need_cmd gzip
  backup_dirs_init
  MYSQL_FILE="$(resolve_backup_file "$MYSQL_SPEC" "$BACKUP_MYSQL_DIR/latest.sql.gz" "$BACKUP_DIR")"
  QDRANT_FILE=""
  if [[ -n "$QDRANT_SPEC" ]]; then
    QDRANT_FILE="$(resolve_backup_file "$QDRANT_SPEC" "$BACKUP_QDRANT_DIR/latest.snapshot" "$BACKUP_QDRANT_DIR")"
  fi
  confirm_restore
  acquire_backup_lock
  service_is_running mysql || die "MySQL 未运行，无法灌入备份。请先启动栈，不要 down -v"
  info "停止 nginx / app，保留 MySQL / Qdrant 数据卷"
  compose stop nginx app || true
  restore_mysql "$MYSQL_FILE"
  if [[ -n "$QDRANT_FILE" ]]; then
    restore_qdrant "$QDRANT_FILE"
  fi
  info "按正常配置启动服务"
  compose up -d
  wait_http "$(base_url)/api/health" 240
  info "恢复完成。建议再跑 ./deploy/smoke.sh --login"
}

main
