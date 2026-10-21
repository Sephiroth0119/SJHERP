#!/usr/bin/env bash
# SJHERP 性能基线（M8-T05）：热路径索引守门、只读 EXPLAIN 冒烟、可选百万行并发压测。
# 文档：docs/部署-性能基线.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

MODE=""
CHECK_ONLY=0
SMOKE_ONLY=0
FULL_ONLY=0

REQUIRED_INDEXES="$DEPLOY_DIR/perf/required-indexes.txt"
EXPLAIN_SMOKE_SQL="$DEPLOY_DIR/perf/explain-smoke.sql"
MIGRATION_DIR="$REPO_ROOT/server/sjherp-infra/src/main/resources/db/migration"

usage() {
  cat <<'EOF'
SJHERP 性能基线（单据过账 / 库存并发 / 慢查询索引）

用法：
  ./deploy/perf.sh --check   语法 + 热路径索引是否写在 Flyway 里（不需要 Docker）
  ./deploy/perf.sh --smoke   对运行中的 compose MySQL 做只读 SHOW INDEX / EXPLAIN
  ./deploy/perf.sh --full    Testcontainers 百万行流水 + 50 并发过账（需 JDK 21 + Docker）
  ./deploy/perf.sh --help

约束：
  --smoke 只读，不往业务表插合成数据，不改 TLS / 备份 / monitor。
  --full 使用独立 Testcontainers MySQL，不碰生产 compose 数据卷。
  不要对生产库做百万行灌数。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1; MODE="check" ;;
    --smoke) SMOKE_ONLY=1; MODE="smoke" ;;
    --full) FULL_ONLY=1; MODE="full" ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$1 （--help 查看用法）" ;;
  esac
  shift
done

if [[ -z "$MODE" ]]; then
  usage
  die "请指定 --check、--smoke 或 --full"
fi

assert_perf_files() {
  [[ -f "$REQUIRED_INDEXES" ]] || die "缺少 $REQUIRED_INDEXES"
  [[ -f "$EXPLAIN_SMOKE_SQL" ]] || die "缺少 $EXPLAIN_SMOKE_SQL"
  [[ -d "$MIGRATION_DIR" ]] || die "缺少 Flyway 目录 $MIGRATION_DIR"
  [[ -f "$MIGRATION_DIR/V38__inventory_txn_src_index.sql" ]] || die "缺少 V38 来源单号索引迁移"
}

sql_catalog() {
  cat "$MIGRATION_DIR"/V*.sql
}

check_required_indexes() {
  local table index line missing=0 catalog
  catalog="$(sql_catalog)"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    table="${line%% *}"
    index="${line#* }"
    [[ -n "$table" && -n "$index" && "$table" != "$index" ]] || die "required-indexes.txt 行格式应为：表名 索引名（$line）"
    if ! grep -Fq "$index" <<<"$catalog"; then
      warn "Flyway 未声明索引 $index（表 $table）"
      missing=1
    fi
  done < "$REQUIRED_INDEXES"
  [[ "$missing" -eq 0 ]] || die "热路径索引清单与 Flyway 不一致"
  info "热路径索引清单与 Flyway 一致"
}

run_check() {
  info "校验性能基线资产"
  bash -n "$DEPLOY_DIR/perf.sh"
  assert_perf_files
  grep -q 'idx_inventory_txn_src' "$MIGRATION_DIR/V38__inventory_txn_src_index.sql" \
    || die "V38 必须包含 idx_inventory_txn_src"
  grep -q 'slow-query-log' "$COMPOSE_FILE" || die "compose MySQL 应打开 slow_query_log"
  grep -q 'long-query-time' "$COMPOSE_FILE" || die "compose MySQL 应设置 long_query_time"
  check_required_indexes
  info "perf --check 通过"
}

mysql_show_index_names() {
  local database="$1"
  local table="$2"
  mysql_cli -N -s -e "SHOW INDEX FROM \`${database}\`.${table}" | awk '{print $3}' | tr -d '\r'
}

assert_live_index() {
  local names="$1"
  local want="$2"
  grep -Fxq "$want" <<<"$names" || die "运行中的 MySQL 缺少索引 $want"
}

explain_mentions_index() {
  local database="$1"
  local sql="$2"
  local want="$3"
  local out
  out="$(mysql_cli -e "USE \`${database}\`; EXPLAIN ${sql}" 2>/dev/null || true)"
  grep -Fq "$want" <<<"$out" || die "EXPLAIN 未提到索引 $want。SQL=${sql}"$'\n'"$out"
}

run_smoke() {
  [[ -f "$ENV_FILE" ]] || die "找不到 deploy/.env。请先 ./deploy/install.sh"
  require_env_keys "$ENV_FILE"
  load_env_file "$ENV_FILE"
  command -v docker >/dev/null 2>&1 || die "未安装 Docker"
  service_is_running mysql || die "MySQL 未运行。--smoke 需要已启动的 compose（先 ./deploy/install.sh）"
  local database
  database="$(mysql_database_name)"
  info "只读检查 ${database} 热路径索引（不 INSERT）"

  local balance_idx txn_idx voucher_idx line_idx
  balance_idx="$(mysql_show_index_names "$database" inventory_balance)"
  txn_idx="$(mysql_show_index_names "$database" inventory_transaction)"
  voucher_idx="$(mysql_show_index_names "$database" voucher)"
  line_idx="$(mysql_show_index_names "$database" voucher_line)"
  assert_live_index "$balance_idx" uk_inventory_balance_dim
  assert_live_index "$txn_idx" uk_inventory_txn_idempotency
  assert_live_index "$txn_idx" idx_inventory_txn_dim
  assert_live_index "$txn_idx" idx_inventory_txn_src
  assert_live_index "$voucher_idx" uk_voucher_source
  assert_live_index "$line_idx" idx_voucher_line_account
  info "SHOW INDEX 通过"

  explain_mentions_index "$database" \
    "SELECT id FROM inventory_balance WHERE tenant_id=0 AND warehouse_id=1 AND product_id=1 AND batch_id=0 FOR UPDATE" \
    "uk_inventory_balance_dim"
  explain_mentions_index "$database" \
    "SELECT id FROM inventory_transaction WHERE tenant_id=0 AND warehouse_id=1 AND product_id=1 AND unit_cost IS NOT NULL ORDER BY id DESC LIMIT 1" \
    "idx_inventory_txn_dim"
  explain_mentions_index "$database" \
    "SELECT id FROM inventory_transaction WHERE tenant_id=0 AND idempotency_key='PERFSEED:missing'" \
    "uk_inventory_txn_idempotency"
  explain_mentions_index "$database" \
    "SELECT id FROM inventory_transaction WHERE tenant_id=0 AND src_doc_no='SD-MISSING' AND src_line_no=1" \
    "idx_inventory_txn_src"
  info "EXPLAIN 冒烟通过（只读）"
  mysql_cli "$database" < "$EXPLAIN_SMOKE_SQL" >/dev/null
  info "perf --smoke 通过"
}

run_full() {
  command -v docker >/dev/null 2>&1 || die "--full 需要 Docker（Testcontainers MySQL 8.4）"
  command -v mvn >/dev/null 2>&1 || die "--full 需要本机 Maven / JDK 21，不会对生产 compose 灌数"
  info "开始百万行 + 50 并发（独立 Testcontainers，不碰 deploy compose 数据卷）"
  (
    cd "$REPO_ROOT/server"
    SJHERP_PERF_SCALE=full mvn -B -ntp test -pl sjherp-app -am \
      -Dtest=DocumentPostingPerfIntegrationTest \
      -Dgroups=integration-db \
      -DexcludedGroups=none \
      -Dsurefire.failIfNoSpecifiedTests=false
  )
  info "perf --full 通过"
}

main() {
  case "$MODE" in
    check) run_check ;;
    smoke)
      run_check
      run_smoke
      ;;
    full)
      run_check
      run_full
      ;;
    *) die "内部错误：未知模式 $MODE" ;;
  esac
}

main
