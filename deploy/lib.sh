# SJHERP 部署脚本公共函数（由 install.sh / upgrade.sh / smoke.sh / backup.sh / restore.sh source）
# shellcheck shell=bash

if [[ -n "${SJHERP_LIB_LOADED:-}" ]]; then
  return 0
fi
SJHERP_LIB_LOADED=1

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"
ENV_EXAMPLE="$DEPLOY_DIR/.env.example"
ENV_FILE="$DEPLOY_DIR/.env"
BACKUP_DIR="$DEPLOY_DIR/backups"
BACKUP_MYSQL_DIR="$BACKUP_DIR/mysql"
BACKUP_QDRANT_DIR="$BACKUP_DIR/qdrant"
BACKUP_QDRANT_LIVE_DIR="$BACKUP_QDRANT_DIR/live"
BACKUP_LOCK_FILE="$BACKUP_DIR/.lock"
DEFAULT_BACKUP_KEEP_DAYS=14

REQUIRED_ENV_KEYS=(
  MYSQL_ROOT_PASSWORD
  SJHERP_DB_PASSWORD
  SJHERP_DB_URL
  SJHERP_DB_USERNAME
  SJHERP_JWT_SECRET
  SJHERP_ADMIN_PASSWORD
)

info() { printf '==> %s\n' "$*"; }
warn() { printf '警告：%s\n' "$*" >&2; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

is_placeholder_secret() {
  local value="${1:-}"
  [[ -z "$value" ]] && return 0
  [[ "$value" == *changeMe* || "$value" == *CHANGE_ME* || "$value" == *example* ]]
}

load_env_file() {
  local file="${1:-$ENV_FILE}"
  [[ -f "$file" ]] || die "找不到环境文件：$file"
  set -a
  # shellcheck disable=SC1090
  source "$file"
  set +a
}

env_lookup() {
  local key="$1"
  local file="${2:-$ENV_FILE}"
  [[ -f "$file" ]] || return 1
  local line value
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  [[ -n "$line" ]] || return 1
  value="${line#*=}"
  if [[ "$value" == \'*\' && ${#value} -ge 2 ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "$value" == \"*\" && ${#value} -ge 2 ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

require_env_keys() {
  local file="$1"
  local key missing=0
  for key in "${REQUIRED_ENV_KEYS[@]}"; do
    if ! grep -qE "^${key}=" "$file"; then
      warn "环境文件缺少 $key：$file"
      missing=1
    fi
  done
  [[ "$missing" -eq 0 ]] || die "环境文件不完整，请对照 deploy/.env.example"
}

docker_bin() {
  if [[ -n "${SJHERP_DOCKER:-}" ]]; then
    printf '%s' "$SJHERP_DOCKER"
    return 0
  fi
  if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then
      printf '%s' "docker"
      return 0
    fi
    if command -v sudo >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
      printf '%s' "sudo docker"
      return 0
    fi
    printf '%s' "docker"
    return 0
  fi
  die "未安装 Docker。请先运行 ./deploy/install.sh（不要加 --check）"
}

compose() {
  local docker_cmd env_file
  docker_cmd="$(docker_bin)"
  env_file="$ENV_FILE"
  if [[ ! -f "$env_file" ]]; then
    env_file="$ENV_EXAMPLE"
  fi
  # project-directory=deploy，这样 .env 与 named volume 都落在同一处
  # shellcheck disable=SC2086
  $docker_cmd compose --project-directory "$DEPLOY_DIR" -f "$COMPOSE_FILE" --env-file "$env_file" "$@"
}

http_port() {
  local port
  port="$(env_lookup SJHERP_HTTP_PORT "$ENV_FILE" 2>/dev/null || true)"
  if [[ -z "$port" ]]; then
    port="$(env_lookup SJHERP_HTTP_PORT "$ENV_EXAMPLE" 2>/dev/null || true)"
  fi
  printf '%s' "${port:-80}"
}

base_url() {
  printf 'http://127.0.0.1:%s' "$(http_port)"
}

wait_http() {
  local url="$1"
  local timeout_sec="${2:-180}"
  local started now
  started="$(date +%s)"
  info "等待服务就绪：$url （最多 ${timeout_sec}s）"
  while true; do
    if command -v curl >/dev/null 2>&1; then
      if curl -fsS --max-time 5 "$url" >/dev/null 2>&1; then
        info "已就绪：$url"
        return 0
      fi
    elif command -v wget >/dev/null 2>&1; then
      if wget -qO- --timeout=5 "$url" >/dev/null 2>&1; then
        info "已就绪：$url"
        return 0
      fi
    else
      die "需要 curl 或 wget 做健康检查"
    fi
    now="$(date +%s)"
    if (( now - started >= timeout_sec )); then
      warn "等待超时。最近容器状态："
      compose ps || true
      warn "app 日志（末尾）："
      compose logs --tail 80 app || true
      die "服务未在 ${timeout_sec}s 内就绪：$url"
    fi
    sleep 3
  done
}

generate_alnum() {
  local raw
  raw="$(openssl rand -base64 24 | tr -d '\n' | tr -dc 'A-Za-z0-9' | head -c 24)"
  printf '%s' "$raw"
}

generate_admin_password() {
  # ≥8 位且必须同时有字母和数字
  printf 'Adm%s' "$(openssl rand -hex 6)"
}

write_env_value() {
  local key="$1"
  local value="$2"
  local file="$3"
  if grep -qE "^${key}=" "$file"; then
    local tmp
    tmp="$(mktemp)"
    awk -v k="$key" -v v="$value" '
      BEGIN { done=0 }
      $0 ~ "^" k "=" {
        if (!done) { print k "=" v; done=1 }
        next
      }
      { print }
      END { if (!done) print k "=" v }
    ' "$file" > "$tmp"
    mv "$tmp" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

backup_dirs_init() {
  mkdir -p "$BACKUP_MYSQL_DIR" "$BACKUP_QDRANT_LIVE_DIR"
}

backup_keep_days() {
  local raw
  raw="$(env_lookup SJHERP_BACKUP_KEEP_DAYS "$ENV_FILE" 2>/dev/null || true)"
  if [[ -z "$raw" && -f "$ENV_EXAMPLE" ]]; then
    raw="$(env_lookup SJHERP_BACKUP_KEEP_DAYS "$ENV_EXAMPLE" 2>/dev/null || true)"
  fi
  raw="${raw:-$DEFAULT_BACKUP_KEEP_DAYS}"
  [[ "$raw" =~ ^[0-9]+$ ]] || die "SJHERP_BACKUP_KEEP_DAYS 必须是正整数，当前：$raw"
  [[ "$raw" -ge 1 && "$raw" -le 365 ]] || die "SJHERP_BACKUP_KEEP_DAYS 应在 1–365 之间，当前：$raw"
  printf '%s' "$raw"
}

acquire_backup_lock() {
  mkdir -p "$BACKUP_DIR"
  exec 9>"$BACKUP_LOCK_FILE"
  if ! flock -n 9; then
    die "已有备份或恢复在运行（锁文件 $BACKUP_LOCK_FILE）"
  fi
}

service_is_running() {
  local name="$1"
  local ps
  ps="$(compose ps "$name" 2>/dev/null || true)"
  grep -Eq 'running|Up' <<<"$ps"
}

mysql_root_password() {
  local password
  password="$(env_lookup MYSQL_ROOT_PASSWORD "$ENV_FILE")"
  [[ -n "$password" ]] || die "deploy/.env 缺少 MYSQL_ROOT_PASSWORD"
  printf '%s' "$password"
}

mysql_database_name() {
  local database
  database="$(env_lookup MYSQL_DATABASE "$ENV_FILE" 2>/dev/null || true)"
  printf '%s' "${database:-sjherp}"
}

mysql_cli() {
  local password
  password="$(mysql_root_password)"
  compose exec -T mysql mysql -uroot "-p${password}" "$@"
}

qdrant_curl() {
  local method="$1"
  local path="$2"
  shift 2
  local url="http://qdrant:6333${path}"
  if service_is_running app; then
    compose exec -T app curl -fsS --max-time 300 -X "$method" "$url" "$@"
  else
    compose run --rm -T --no-deps --entrypoint curl app -fsS --max-time 300 -X "$method" "$url" "$@"
  fi
}

wait_qdrant_ready() {
  local timeout_sec="${1:-90}"
  local started now
  started="$(date +%s)"
  info "等待 Qdrant 就绪（最多 ${timeout_sec}s）"
  while true; do
    if qdrant_curl GET /readyz >/dev/null 2>&1; then
      info "Qdrant 已就绪"
      return 0
    fi
    now="$(date +%s)"
    if (( now - started >= timeout_sec )); then
      compose ps qdrant || true
      compose logs --tail 80 qdrant || true
      die "Qdrant 未在 ${timeout_sec}s 内就绪"
    fi
    sleep 3
  done
}

json_result_name() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; data=json.load(sys.stdin); print((data.get("result") or {}).get("name") or "")'
  else
    grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/'
  fi
}
