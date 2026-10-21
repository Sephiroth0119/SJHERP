#!/usr/bin/env bash
# SJHERP 备份：MySQL dump + Qdrant 全库快照 + 按天保留。
# 文档：docs/部署-备份恢复.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0
DO_MYSQL=1
DO_QDRANT=1
DO_PRUNE=1
ALLOW_SKIP_IF_DOWN=0
REASON="daily"
KEEP_DAYS_OVERRIDE=""

usage() {
  cat <<'EOF'
SJHERP 备份（MySQL 真源 + Qdrant 派生索引快照）

用法：
  ./deploy/backup.sh                    备份 MySQL 与 Qdrant，并按保留天数清理
  ./deploy/backup.sh --mysql-only       只备份 MySQL
  ./deploy/backup.sh --qdrant-only      只做 Qdrant 快照
  ./deploy/backup.sh --reason pre-upgrade
                                        文件名带原因（daily / pre-upgrade）
  ./deploy/backup.sh --keep-days 14     覆盖 SJHERP_BACKUP_KEEP_DAYS
  ./deploy/backup.sh --skip-prune       不删除过期备份
  ./deploy/backup.sh --allow-skip-if-down
                                        对应服务未运行时跳过该项（升级脚本用）
  ./deploy/backup.sh --check            只校验，不写备份
  ./deploy/backup.sh --help

落盘：
  deploy/backups/mysql/sjherp-<reason>-<UTC时间>.sql.gz
  deploy/backups/qdrant/sjherp-<reason>-<UTC时间>.snapshot
  以及 latest.sql.gz / latest.snapshot 符号链接，便于恢复演练。

定时：把 deploy/crontab.example 装到宿主机 crontab。不要用 docker compose down -v 当备份手段。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --mysql-only) DO_QDRANT=0 ;;
    --qdrant-only) DO_MYSQL=0 ;;
    --skip-prune) DO_PRUNE=0 ;;
    --allow-skip-if-down) ALLOW_SKIP_IF_DOWN=1 ;;
    --help|-h) usage; exit 0 ;;
    --reason)
      shift
      REASON="${1:-}"
      [[ -n "$REASON" ]] || die "--reason 不能为空"
      ;;
    --reason=*)
      REASON="${1#--reason=}"
      [[ -n "$REASON" ]] || die "--reason 不能为空"
      ;;
    --keep-days)
      shift
      KEEP_DAYS_OVERRIDE="${1:-}"
      [[ -n "$KEEP_DAYS_OVERRIDE" ]] || die "--keep-days 不能为空"
      ;;
    --keep-days=*)
      KEEP_DAYS_OVERRIDE="${1#--keep-days=}"
      [[ -n "$KEEP_DAYS_OVERRIDE" ]] || die "--keep-days 不能为空"
      ;;
    *)
      die "未知参数：$1 （--help 查看用法）"
      ;;
  esac
  shift
done

if [[ "$DO_MYSQL" -eq 0 && "$DO_QDRANT" -eq 0 ]]; then
  die "不能同时 --mysql-only 和 --qdrant-only"
fi

[[ "$REASON" =~ ^[a-z0-9-]+$ ]] || die "reason 只允许小写字母、数字和连字符，当前：$REASON"

keep_days() {
  if [[ -n "$KEEP_DAYS_OVERRIDE" ]]; then
    [[ "$KEEP_DAYS_OVERRIDE" =~ ^[0-9]+$ ]] || die "--keep-days 必须是正整数"
    [[ "$KEEP_DAYS_OVERRIDE" -ge 1 && "$KEEP_DAYS_OVERRIDE" -le 365 ]] || die "--keep-days 应在 1–365 之间"
    printf '%s' "$KEEP_DAYS_OVERRIDE"
  else
    backup_keep_days
  fi
}

refresh_latest_link() {
  local dir="$1"
  local glob="$2"
  local link_name="$3"
  local newest
  newest="$(ls -1t "$dir"/$glob 2>/dev/null | head -n 1 || true)"
  if [[ -n "$newest" ]]; then
    ln -sfn "$(basename "$newest")" "$dir/$link_name"
  else
    rm -f "$dir/$link_name"
  fi
}

prune_backups() {
  local days
  days="$(keep_days)"
  info "清理 ${days} 天前的备份"
  find "$BACKUP_DIR" "$BACKUP_MYSQL_DIR" -maxdepth 1 -type f \( \
      -name 'sjherp-*.sql.gz' -o -name 'sjherp-*.sql' \
    \) -mtime "+${days}" -print -delete 2>/dev/null || true
  find "$BACKUP_QDRANT_DIR" -maxdepth 1 -type f -name 'sjherp-*.snapshot' \
    -mtime "+${days}" -print -delete 2>/dev/null || true
  refresh_latest_link "$BACKUP_MYSQL_DIR" 'sjherp-*.sql.gz' latest.sql.gz
  refresh_latest_link "$BACKUP_QDRANT_DIR" 'sjherp-*.snapshot' latest.snapshot
}

backup_mysql() {
  if ! service_is_running mysql; then
    if [[ "$ALLOW_SKIP_IF_DOWN" -eq 1 ]]; then
      warn "MySQL 容器未在运行，跳过 dump"
      return 0
    fi
    die "MySQL 容器未在运行。请先 ./deploy/install.sh，或确认 compose ps"
  fi
  need_cmd gzip
  backup_dirs_init
  local stamp out_sql out_gz password database
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  out_sql="$BACKUP_MYSQL_DIR/sjherp-${REASON}-${stamp}.sql"
  out_gz="${out_sql}.gz"
  password="$(mysql_root_password)"
  database="$(mysql_database_name)"
  info "备份 MySQL（${database}）→ $out_gz"
  if compose exec -T mysql mysqldump \
      -uroot "-p${password}" \
      --single-transaction --routines --events --hex-blob \
      --add-drop-database --databases "$database" \
      | gzip -c > "$out_gz" && [[ -s "$out_gz" ]]; then
    gzip -t "$out_gz"
    ln -sfn "$(basename "$out_gz")" "$BACKUP_MYSQL_DIR/latest.sql.gz"
    info "MySQL 备份完成：$(du -h "$out_gz" | awk '{print $1}')  $out_gz"
  else
    rm -f "$out_gz"
    die "mysqldump 失败"
  fi
}

backup_qdrant() {
  if ! service_is_running qdrant; then
    if [[ "$ALLOW_SKIP_IF_DOWN" -eq 1 ]]; then
      warn "Qdrant 容器未在运行，跳过快照"
      return 0
    fi
    die "Qdrant 容器未在运行。请先 ./deploy/install.sh"
  fi
  backup_dirs_init
  local stamp resp name src dest
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  info "创建 Qdrant 全库快照"
  resp="$(qdrant_curl POST /snapshots -H 'Content-Type: application/json')"
  name="$(json_result_name <<<"$resp")"
  [[ -n "$name" ]] || die "Qdrant 快照响应没有 name：$resp"
  src="$BACKUP_QDRANT_LIVE_DIR/$name"
  dest="$BACKUP_QDRANT_DIR/sjherp-${REASON}-${stamp}.snapshot"
  local waited=0
  while [[ ! -s "$src" ]]; do
    if (( waited >= 60 )); then
      die "等不到 Qdrant 快照文件：$src （响应：$resp）"
    fi
    sleep 1
    waited=$((waited + 1))
  done
  cp -f "$src" "$dest"
  [[ -s "$dest" ]] || die "Qdrant 快照复制后为空：$dest"
  ln -sfn "$(basename "$dest")" "$BACKUP_QDRANT_DIR/latest.snapshot"
  if qdrant_curl DELETE "/snapshots/${name}" >/dev/null 2>&1; then
    info "已从 Qdrant live 目录删除临时快照 $name"
  else
    warn "未能删除 Qdrant live 快照 $name，可稍后手工清 deploy/backups/qdrant/live/"
    rm -f "$src" || true
  fi
  info "Qdrant 快照完成：$(du -h "$dest" | awk '{print $1}')  $dest"
}

print_check() {
  backup_dirs_init
  info "备份目录：$BACKUP_DIR"
  info "MySQL 备份：$BACKUP_MYSQL_DIR"
  info "Qdrant 快照：$BACKUP_QDRANT_DIR （live bind：$BACKUP_QDRANT_LIVE_DIR）"
  info "默认保留天数：$(keep_days)"
  bash -n "$DEPLOY_DIR/backup.sh"
}

main() {
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    print_check
    info "backup --check 通过"
    exit 0
  fi
  [[ -f "$ENV_FILE" ]] || die "找不到 deploy/.env。请先 ./deploy/install.sh"
  require_env_keys "$ENV_FILE"
  load_env_file "$ENV_FILE"
  command -v docker >/dev/null 2>&1 || die "未安装 Docker"
  command -v flock >/dev/null 2>&1 || die "缺少 flock（util-linux）"
  backup_dirs_init
  acquire_backup_lock
  if [[ "$DO_MYSQL" -eq 1 ]]; then
    backup_mysql
  fi
  if [[ "$DO_QDRANT" -eq 1 ]]; then
    backup_qdrant
  fi
  if [[ "$DO_PRUNE" -eq 1 ]]; then
    prune_backups
  fi
  info "备份完成"
}

main
