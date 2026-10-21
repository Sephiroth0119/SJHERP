#!/usr/bin/env bash
# SJHERP 监控：健康、磁盘、备份新鲜度、近期 ERROR 日志；可选 webhook。
# 文档：docs/部署-监控告警.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0
SELF_TEST=0
ALLOW_MISSING_BACKUP=0
SKIP_HEALTH=0
SKIP_LOGS=0
SKIP_METRICS=0
FORCE_ALERT=0
BACKUP_ROOT_OVERRIDE=""

DEFAULT_DISK_MIN_MB=1024
DEFAULT_BACKUP_MAX_AGE_HOURS=26
DEFAULT_ERROR_LOG_MAX=50
DEFAULT_ERROR_LOG_SINCE="15m"

usage() {
  cat <<'EOF'
SJHERP 监控巡检（健康 / 磁盘 / 备份新鲜度 / 错误日志）

用法：
  ./deploy/monitor.sh                 对当前部署做一次巡检，失败则 exit 1
  ./deploy/monitor.sh --check         只校验脚本与阈值，不要求服务在跑
  ./deploy/monitor.sh --self-test     用临时目录验证过期备份/磁盘阈值逻辑
  ./deploy/monitor.sh --allow-missing-backup
                                      没有 dump 时记 WARN 而不失败（刚装完用）
  ./deploy/monitor.sh --skip-health   不打 /api/health
  ./deploy/monitor.sh --skip-logs     不统计容器 ERROR
  ./deploy/monitor.sh --skip-metrics  不刮 /actuator/prometheus
  ./deploy/monitor.sh --force-alert   忽略去重，失败时必发 webhook
  ./deploy/monitor.sh --help

阈值（deploy/.env，均可选）：
  SJHERP_DISK_MIN_MB              备份盘可用下限，默认 1024
  SJHERP_BACKUP_MAX_AGE_HOURS     MySQL dump 最大年龄，默认 26
  SJHERP_ERROR_LOG_MAX            近窗 ERROR 行数上限，默认 50
  SJHERP_ERROR_LOG_SINCE          docker logs --since，默认 15m
  SJHERP_ALERT_WEBHOOK            失败时 POST JSON {text,status,source}

定时：见 deploy/crontab.example。不要引入 Prometheus/Grafana 容器。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --self-test) SELF_TEST=1 ;;
    --allow-missing-backup) ALLOW_MISSING_BACKUP=1 ;;
    --skip-health) SKIP_HEALTH=1 ;;
    --skip-logs) SKIP_LOGS=1 ;;
    --skip-metrics) SKIP_METRICS=1 ;;
    --force-alert) FORCE_ALERT=1 ;;
    --help|-h) usage; exit 0 ;;
    --backup-root)
      shift
      BACKUP_ROOT_OVERRIDE="${1:-}"
      [[ -n "$BACKUP_ROOT_OVERRIDE" ]] || die "--backup-root 不能为空"
      ;;
    --backup-root=*)
      BACKUP_ROOT_OVERRIDE="${1#--backup-root=}"
      [[ -n "$BACKUP_ROOT_OVERRIDE" ]] || die "--backup-root 不能为空"
      ;;
    *)
      die "未知参数：$1 （--help 查看用法）"
      ;;
  esac
  shift
done

FAILS=0
WARNS=0
REPORT=""

append_report() {
  local line="$1"
  REPORT="${REPORT}${line}"$'\n'
}

ok() {
  info "$1"
  append_report "OK  $1"
}

warn_check() {
  WARNS=$((WARNS + 1))
  warn "$1"
  append_report "WARN  $1"
}

fail_check() {
  FAILS=$((FAILS + 1))
  printf '错误：%s\n' "$1" >&2
  append_report "FAIL  $1"
}

positive_int_or_default() {
  local raw="$1"
  local fallback="$2"
  local name="$3"
  if [[ -z "$raw" ]]; then
    printf '%s' "$fallback"
    return 0
  fi
  [[ "$raw" =~ ^[0-9]+$ ]] || die "$name 必须是非负整数，当前：$raw"
  printf '%s' "$raw"
}

disk_min_mb() {
  local raw=""
  if [[ -f "$ENV_FILE" ]]; then
    raw="$(env_lookup SJHERP_DISK_MIN_MB "$ENV_FILE" 2>/dev/null || true)"
  elif [[ -f "$ENV_EXAMPLE" ]]; then
    raw="$(env_lookup SJHERP_DISK_MIN_MB "$ENV_EXAMPLE" 2>/dev/null || true)"
  fi
  positive_int_or_default "$raw" "$DEFAULT_DISK_MIN_MB" "SJHERP_DISK_MIN_MB"
}

backup_max_age_hours() {
  local raw=""
  if [[ -f "$ENV_FILE" ]]; then
    raw="$(env_lookup SJHERP_BACKUP_MAX_AGE_HOURS "$ENV_FILE" 2>/dev/null || true)"
  elif [[ -f "$ENV_EXAMPLE" ]]; then
    raw="$(env_lookup SJHERP_BACKUP_MAX_AGE_HOURS "$ENV_EXAMPLE" 2>/dev/null || true)"
  fi
  local value
  value="$(positive_int_or_default "$raw" "$DEFAULT_BACKUP_MAX_AGE_HOURS" "SJHERP_BACKUP_MAX_AGE_HOURS")"
  [[ "$value" -ge 1 ]] || die "SJHERP_BACKUP_MAX_AGE_HOURS 至少为 1"
  printf '%s' "$value"
}

error_log_max() {
  local raw=""
  if [[ -f "$ENV_FILE" ]]; then
    raw="$(env_lookup SJHERP_ERROR_LOG_MAX "$ENV_FILE" 2>/dev/null || true)"
  fi
  positive_int_or_default "$raw" "$DEFAULT_ERROR_LOG_MAX" "SJHERP_ERROR_LOG_MAX"
}

error_log_since() {
  local raw=""
  if [[ -f "$ENV_FILE" ]]; then
    raw="$(env_lookup SJHERP_ERROR_LOG_SINCE "$ENV_FILE" 2>/dev/null || true)"
  fi
  printf '%s' "${raw:-$DEFAULT_ERROR_LOG_SINCE}"
}

metrics_port() {
  local port=""
  if [[ -f "$ENV_FILE" ]]; then
    port="$(env_lookup SJHERP_METRICS_PORT "$ENV_FILE" 2>/dev/null || true)"
  fi
  if [[ -z "$port" && -f "$ENV_EXAMPLE" ]]; then
    port="$(env_lookup SJHERP_METRICS_PORT "$ENV_EXAMPLE" 2>/dev/null || true)"
  fi
  printf '%s' "${port:-8081}"
}

monitor_backup_root() {
  if [[ -n "$BACKUP_ROOT_OVERRIDE" ]]; then
    printf '%s' "$BACKUP_ROOT_OVERRIDE"
  else
    printf '%s' "$BACKUP_DIR"
  fi
}

file_age_hours() {
  local file="$1"
  if [[ -L "$file" ]]; then
    local target
    target="$(readlink -f "$file" 2>/dev/null || readlink "$file")"
    [[ -n "$target" ]] || return 1
    if [[ "$target" != /* ]]; then
      target="$(dirname "$file")/$target"
    fi
    file="$target"
  fi
  [[ -f "$file" ]] || return 1
  local mtime now
  mtime="$(stat -c %Y "$file")"
  now="$(date +%s)"
  printf '%s' $(( (now - mtime) / 3600 ))
}

check_disk() {
  local root="$1"
  mkdir -p "$root"
  local avail_kb min_mb min_kb
  avail_kb="$(df -Pk "$root" | awk 'NR==2 {print $4}')"
  [[ "$avail_kb" =~ ^[0-9]+$ ]] || {
    fail_check "无法读取磁盘可用空间：$root"
    return 0
  }
  min_mb="$(disk_min_mb)"
  min_kb=$((min_mb * 1024))
  if (( avail_kb < min_kb )); then
    fail_check "磁盘可用 $((avail_kb / 1024))MB < 阈值 ${min_mb}MB（$root）"
  else
    ok "磁盘可用 $((avail_kb / 1024))MB（阈值 ${min_mb}MB）"
  fi
}

check_backup_file() {
  local label="$1"
  local file="$2"
  local max_hours="$3"
  local allow_missing="$4"
  if [[ ! -e "$file" ]]; then
    if [[ "$allow_missing" -eq 1 ]]; then
      warn_check "${label} 尚无备份文件（$file）"
    else
      fail_check "${label} 缺少备份：$file"
    fi
    return 0
  fi
  local age
  if ! age="$(file_age_hours "$file")"; then
    fail_check "${label} 无法读取备份时间：$file"
    return 0
  fi
  if (( age > max_hours )); then
    fail_check "${label} 备份已 ${age} 小时（阈值 ${max_hours}h）：$file"
  else
    ok "${label} 备份新鲜（${age}h / 阈值 ${max_hours}h）"
  fi
}

check_health() {
  if [[ "$SKIP_HEALTH" -eq 1 ]]; then
    return 0
  fi
  if [[ ! -f "$ENV_FILE" ]]; then
    warn_check "没有 deploy/.env，跳过健康检查"
    return 0
  fi
  need_cmd curl
  local url health
  url="$(base_url)/api/health"
  # shellcheck disable=SC2046,SC2086
  if ! health="$(curl -fsS --max-time 8 $(curl_tls_args "$url") "$url" 2>/dev/null)"; then
    fail_check "健康检查失败：$url"
    return 0
  fi
  if grep -q '"status":"UP"' <<<"$health" || grep -q '"status": "UP"' <<<"$health"; then
    ok "健康检查 UP（$url）"
  else
    fail_check "健康检查未返回 UP：$health"
  fi
}

check_metrics() {
  if [[ "$SKIP_METRICS" -eq 1 ]]; then
    return 0
  fi
  if ! command -v curl >/dev/null 2>&1; then
    warn_check "没有 curl，跳过 Prometheus 刮取"
    return 0
  fi
  local url body
  url="http://127.0.0.1:$(metrics_port)/actuator/prometheus"
  if ! body="$(curl -fsS --max-time 5 "$url" 2>/dev/null)"; then
    warn_check "Prometheus 端口不可达：$url（应用仍可能经 nginx 正常；确认 compose 映射了 127.0.0.1:$(metrics_port)）"
    return 0
  fi
  if grep -q 'http_server_requests' <<<"$body"; then
    ok "Prometheus 文本含 http_server_requests"
  else
    warn_check "Prometheus 已响应但还没有 http_server_requests（刚启动常见）"
  fi
}

check_error_logs() {
  if [[ "$SKIP_LOGS" -eq 1 ]]; then
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    warn_check "没有 docker，跳过 ERROR 日志统计"
    return 0
  fi
  if ! service_is_running app; then
    warn_check "app 容器未运行，跳过 ERROR 日志统计"
    return 0
  fi
  local since max count
  since="$(error_log_since)"
  max="$(error_log_max)"
  count="$(compose logs app --since "$since" 2>/dev/null | grep -c ' ERROR ' || true)"
  [[ "$count" =~ ^[0-9]+$ ]] || count=0
  if (( count > max )); then
    fail_check "近 ${since} app ERROR 日志 ${count} 行（阈值 ${max}）"
  else
    ok "近 ${since} app ERROR 日志 ${count} 行（阈值 ${max}）"
  fi
}

alert_state_file() {
  printf '%s' "$(monitor_backup_root)/.last-alert-signature"
}

maybe_alert() {
  local webhook=""
  if [[ -f "$ENV_FILE" ]]; then
    webhook="$(env_lookup SJHERP_ALERT_WEBHOOK "$ENV_FILE" 2>/dev/null || true)"
  fi
  if [[ -z "$webhook" || "$FAILS" -eq 0 ]]; then
    return 0
  fi
  local signature status_label payload
  signature="$(printf '%s' "$REPORT" | sha256sum | awk '{print $1}')"
  local state
  state="$(alert_state_file)"
  if [[ "$FORCE_ALERT" -eq 0 && -f "$state" ]] && grep -qx "$signature" "$state"; then
    info "告警内容与上次相同，跳过 webhook（--force-alert 可强制）"
    return 0
  fi
  status_label="CRIT"
  payload="$(printf '{"text":%s,"status":"%s","source":"sjherp-monitor"}' \
    "$(python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <<<"SJHERP 监控失败
$REPORT")" \
    "$status_label")"
  if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
    if curl -fsS --max-time 8 -H 'Content-Type: application/json' -d "$payload" "$webhook" >/dev/null; then
      mkdir -p "$(dirname "$state")"
      printf '%s\n' "$signature" > "$state"
      info "已发送告警 webhook"
    else
      warn "告警 webhook 发送失败（不阻断 exit code）"
    fi
  else
    warn "缺少 curl/python3，无法发送 webhook"
  fi
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/mysql" "$tmp/qdrant"
  echo 'ok' | gzip > "$tmp/mysql/sjherp-daily-fresh.sql.gz"
  ln -sfn sjherp-daily-fresh.sql.gz "$tmp/mysql/latest.sql.gz"
  echo 'ok' | gzip > "$tmp/qdrant/sjherp-daily-fresh.snapshot"
  ln -sfn sjherp-daily-fresh.snapshot "$tmp/qdrant/latest.snapshot"

  BACKUP_ROOT_OVERRIDE="$tmp"
  ALLOW_MISSING_BACKUP=0
  SKIP_HEALTH=1
  SKIP_LOGS=1
  SKIP_METRICS=1
  FAILS=0
  WARNS=0
  REPORT=""
  check_disk "$tmp"
  check_backup_file "MySQL" "$tmp/mysql/latest.sql.gz" 26 0
  check_backup_file "Qdrant" "$tmp/qdrant/latest.snapshot" 26 0
  [[ "$FAILS" -eq 0 ]] || die "self-test 新鲜备份应通过，实际 FAIL=$FAILS"

  touch -d '40 hours ago' "$tmp/mysql/sjherp-daily-fresh.sql.gz"
  FAILS=0
  REPORT=""
  check_backup_file "MySQL" "$tmp/mysql/latest.sql.gz" 26 0
  [[ "$FAILS" -eq 1 ]] || die "self-test 过期备份应失败，实际 FAIL=$FAILS"

  rm -f "$tmp/mysql/latest.sql.gz" "$tmp/mysql/sjherp-daily-fresh.sql.gz"
  FAILS=0
  REPORT=""
  check_backup_file "MySQL" "$tmp/mysql/latest.sql.gz" 26 1
  [[ "$FAILS" -eq 0 && "$WARNS" -ge 1 ]] || die "self-test 缺备份+allow 应为 WARN"

  FAILS=0
  WARNS=0
  REPORT=""
  check_backup_file "MySQL" "$tmp/mysql/latest.sql.gz" 26 0
  [[ "$FAILS" -eq 1 ]] || die "self-test 缺备份应失败"

  info "monitor --self-test 通过"
}

print_check() {
  bash -n "$DEPLOY_DIR/monitor.sh"
  info "磁盘下限：$(disk_min_mb)MB"
  info "备份最大年龄：$(backup_max_age_hours)h"
  info "ERROR 日志阈值：$(error_log_max) / $(error_log_since)"
  info "Prometheus 端口：$(metrics_port)（仅 127.0.0.1）"
  info "monitor --check 通过"
}

main() {
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    print_check
    run_self_test
    exit 0
  fi
  if [[ "$SELF_TEST" -eq 1 ]]; then
    run_self_test
    exit 0
  fi

  local root max_hours
  root="$(monitor_backup_root)"
  mkdir -p "$root/mysql" "$root/qdrant"
  max_hours="$(backup_max_age_hours)"

  check_disk "$root"
  check_backup_file "MySQL" "$root/mysql/latest.sql.gz" "$max_hours" "$ALLOW_MISSING_BACKUP"
  check_backup_file "Qdrant" "$root/qdrant/latest.snapshot" "$max_hours" "$ALLOW_MISSING_BACKUP"
  check_health
  check_metrics
  check_error_logs
  maybe_alert

  if [[ "$FAILS" -gt 0 ]]; then
    die "监控巡检失败（FAIL=${FAILS} WARN=${WARNS}）"
  fi
  info "监控巡检通过（WARN=${WARNS}）"
}

main
