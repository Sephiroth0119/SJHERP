#!/usr/bin/env bash
# SJHERP 恢复演练冒烟：备份 → 植入金丝雀 → 恢复 → 金丝雀消失 + 健康/登录。
# 文档：docs/部署-备份恢复.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0

usage() {
  cat <<'EOF'
SJHERP 备份/恢复演练冒烟

用法：
  ./deploy/restore-smoke.sh         对当前已启动的生产 compose 跑一遍可验证恢复
  ./deploy/restore-smoke.sh --check 只校验脚本
  ./deploy/restore-smoke.sh --help

步骤：
  1. ./deploy/backup.sh
  2. 在 MySQL 写入 sjherp_restore_drill_canary（不进备份）
  3. ./deploy/restore.sh --mysql latest --qdrant latest --yes
  4. 断言金丝雀表消失，flyway/sys_user 行数与备份前一致
  5. ./deploy/smoke.sh --login
EOF
}

for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$arg （--help 查看用法）" ;;
  esac
done

mysql_scalar() {
  mysql_cli -N -s -e "$1" | tr -d '\r'
}

main() {
  bash -n "$DEPLOY_DIR/backup.sh"
  bash -n "$DEPLOY_DIR/restore.sh"
  bash -n "$DEPLOY_DIR/restore-smoke.sh"
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    "$DEPLOY_DIR/backup.sh" --check
    "$DEPLOY_DIR/restore.sh" --check
    info "restore-smoke --check 通过"
    exit 0
  fi

  [[ -f "$ENV_FILE" ]] || die "找不到 deploy/.env。请先 ./deploy/install.sh"
  require_env_keys "$ENV_FILE"
  load_env_file "$ENV_FILE"
  command -v docker >/dev/null 2>&1 || die "未安装 Docker"
  service_is_running mysql || die "MySQL 未运行，请先 ./deploy/install.sh"
  service_is_running qdrant || die "Qdrant 未运行，请先 ./deploy/install.sh"
  service_is_running app || die "app 未运行，请先 ./deploy/install.sh"

  local database flyway_before users_before flyway_after users_after canary
  database="$(mysql_database_name)"

  info "记录恢复前基线"
  mysql_cli -e "DROP TABLE IF EXISTS \`${database}\`.sjherp_restore_drill_canary;"
  flyway_before="$(mysql_scalar "SELECT COUNT(*) FROM \`${database}\`.flyway_schema_history")"
  users_before="$(mysql_scalar "SELECT COUNT(*) FROM \`${database}\`.sys_user")"
  [[ "$flyway_before" =~ ^[0-9]+$ && "$flyway_before" -gt 0 ]] || die "flyway_schema_history 行数异常：$flyway_before"
  [[ "$users_before" =~ ^[0-9]+$ && "$users_before" -gt 0 ]] || die "sys_user 行数异常：$users_before"
  info "基线 flyway_schema_history=${flyway_before} sys_user=${users_before}"

  "$DEPLOY_DIR/backup.sh" --reason drill --skip-prune
  [[ -s "$BACKUP_MYSQL_DIR/latest.sql.gz" ]] || die "没有生成 MySQL 备份"
  gzip -t "$BACKUP_MYSQL_DIR/latest.sql.gz"
  [[ -s "$BACKUP_QDRANT_DIR/latest.snapshot" ]] || die "没有生成 Qdrant 快照"

  info "写入恢复金丝雀表（应在恢复后消失）"
  mysql_cli -e "CREATE TABLE \`${database}\`.sjherp_restore_drill_canary (id INT PRIMARY KEY); INSERT INTO \`${database}\`.sjherp_restore_drill_canary VALUES (1);"
  canary="$(mysql_scalar "SELECT COUNT(*) FROM \`${database}\`.sjherp_restore_drill_canary")"
  [[ "$canary" == "1" ]] || die "金丝雀写入失败：$canary"

  "$DEPLOY_DIR/restore.sh" --mysql latest --qdrant latest --yes

  canary="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${database}' AND table_name='sjherp_restore_drill_canary'")"
  [[ "$canary" == "0" ]] || die "恢复后金丝雀表仍在，说明没有真正灌回备份"
  flyway_after="$(mysql_scalar "SELECT COUNT(*) FROM \`${database}\`.flyway_schema_history")"
  users_after="$(mysql_scalar "SELECT COUNT(*) FROM \`${database}\`.sys_user")"
  [[ "$flyway_after" == "$flyway_before" ]] || die "flyway 行数不一致：恢复前 ${flyway_before} / 恢复后 ${flyway_after}"
  [[ "$users_after" == "$users_before" ]] || die "sys_user 行数不一致：恢复前 ${users_before} / 恢复后 ${users_after}"
  info "金丝雀已消失，行数与备份前一致"

  "$DEPLOY_DIR/smoke.sh" --login
  info "恢复演练冒烟通过"
}

main
