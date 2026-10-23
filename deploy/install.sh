#!/usr/bin/env bash
# SJHERP 一键安装：依赖 → 密钥 → 构建启动 → 健康检查。
# 文档：docs/部署-一键安装.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0
SKIP_DOCKER_INSTALL=0

usage() {
  cat <<'EOF'
SJHERP 一键安装（生产 docker compose：nginx / app / mysql / qdrant）

用法：
  ./deploy/install.sh                 安装 Docker（若缺）、生成密钥、启动并冒烟
  ./deploy/install.sh --check         只校验脚本和 compose，不装 Docker、不启动
  ./deploy/install.sh --skip-docker-install
                                      不自动安装 Docker（本机已有时使用）
  ./deploy/install.sh --help

默认在仓库根目录执行。全新 Ubuntu 建议：
  sudo apt-get update && sudo apt-get install -y git curl ca-certificates
  git clone <本仓库> && cd SJHERP
  ./deploy/install.sh
EOF
}

for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --skip-docker-install) SKIP_DOCKER_INSTALL=1 ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$arg （--help 查看用法）" ;;
  esac
done

assert_files() {
  [[ -f "$COMPOSE_FILE" ]] || die "缺少 $COMPOSE_FILE"
  [[ -f "$ENV_EXAMPLE" ]] || die "缺少 $ENV_EXAMPLE"
  [[ -f "$DEPLOY_DIR/app/Dockerfile" ]] || die "缺少后端 Dockerfile"
  [[ -f "$DEPLOY_DIR/nginx/Dockerfile" ]] || die "缺少 nginx Dockerfile"
  [[ -f "$DEPLOY_DIR/nginx/nginx.conf" ]] || die "缺少 nginx.conf"
  [[ -f "$DEPLOY_DIR/nginx/nginx-tls.conf" ]] || die "缺少 nginx-tls.conf"
  [[ -f "$DEPLOY_DIR/nginx/api-proxy.conf.inc" ]] || die "缺少 api-proxy.conf.inc"
  [[ -f "$DEPLOY_DIR/nginx/40-sjherp-tls.sh" ]] || die "缺少 nginx TLS 入口脚本"
  [[ -f "$DEPLOY_DIR/backup.sh" ]] || die "缺少 backup.sh"
  [[ -f "$DEPLOY_DIR/restore.sh" ]] || die "缺少 restore.sh"
  [[ -f "$DEPLOY_DIR/restore-smoke.sh" ]] || die "缺少 restore-smoke.sh"
  [[ -f "$DEPLOY_DIR/monitor.sh" ]] || die "缺少 monitor.sh"
  [[ -f "$DEPLOY_DIR/perf.sh" ]] || die "缺少 perf.sh"
  [[ -f "$DEPLOY_DIR/seed-pilot.sh" ]] || die "缺少 seed-pilot.sh"
  [[ -d "$REPO_ROOT/server" ]] || die "缺少 server/ ，请在仓库根执行"
  [[ -d "$REPO_ROOT/web" ]] || die "缺少 web/ ，请在仓库根执行"
}

check_assets() {
  info "校验部署资产"
  assert_files
  bash -n "$DEPLOY_DIR/lib.sh"
  bash -n "$DEPLOY_DIR/install.sh"
  bash -n "$DEPLOY_DIR/upgrade.sh"
  bash -n "$DEPLOY_DIR/smoke.sh"
  bash -n "$DEPLOY_DIR/backup.sh"
  bash -n "$DEPLOY_DIR/restore.sh"
  bash -n "$DEPLOY_DIR/restore-smoke.sh"
  bash -n "$DEPLOY_DIR/monitor.sh"
  bash -n "$DEPLOY_DIR/perf.sh"
  bash -n "$DEPLOY_DIR/seed-pilot.sh"
  sh -n "$DEPLOY_DIR/nginx/40-sjherp-tls.sh"
  require_env_keys "$ENV_EXAMPLE"
  "$DEPLOY_DIR/backup.sh" --check
  "$DEPLOY_DIR/restore.sh" --check
  "$DEPLOY_DIR/restore-smoke.sh" --check
  "$DEPLOY_DIR/monitor.sh" --check
  "$DEPLOY_DIR/perf.sh" --check
  "$DEPLOY_DIR/seed-pilot.sh" --check
  if command -v docker >/dev/null 2>&1; then
    info "校验 docker compose 配置"
    compose config >/dev/null
    info "compose 配置通过"
  else
    warn "当前环境没有 docker 命令，跳过 compose config（安装时会自动安装 Docker）"
  fi
  info "--check 通过"
}

install_prereqs() {
  if command -v curl >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v apt-get >/dev/null 2>&1; then
    die "需要 curl 与 openssl。当前系统没有 apt-get，请手动安装后重试"
  fi
  info "安装基础命令（curl openssl ca-certificates）"
  if [[ "$(id -u)" -eq 0 ]]; then
    apt-get update -y
    apt-get install -y curl openssl ca-certificates
  elif command -v sudo >/dev/null 2>&1; then
    sudo apt-get update -y
    sudo apt-get install -y curl openssl ca-certificates
  else
    die "缺少 curl/openssl，且没有 sudo 无法自动安装"
  fi
}

install_docker() {
  if command -v docker >/dev/null 2>&1; then
    info "已检测到 Docker：$(docker --version 2>/dev/null || true)"
    return 0
  fi
  [[ "$SKIP_DOCKER_INSTALL" -eq 0 ]] || die "未安装 Docker。去掉 --skip-docker-install，或先自行安装 Docker Engine + Compose 插件"
  [[ "$(uname -s)" == "Linux" ]] || die "自动安装 Docker 仅支持 Linux（文档以 Ubuntu 为准）"
  info "未检测到 Docker，开始安装 Docker Engine（需要网络和 sudo）"
  install_prereqs
  if [[ "$(id -u)" -eq 0 ]]; then
    curl -fsSL https://get.docker.com | sh
  elif command -v sudo >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sudo sh
  else
    die "安装 Docker 需要 root 或 sudo"
  fi
  if command -v sudo >/dev/null 2>&1 && [[ "$(id -u)" -ne 0 ]]; then
    sudo usermod -aG docker "$USER" || true
    warn "已把当前用户加入 docker 组。若本轮仍需 sudo docker，注销重登后即可免 sudo"
  fi
  command -v docker >/dev/null 2>&1 || die "Docker 安装后仍找不到 docker 命令"
  info "Docker 安装完成：$(docker --version)"
}

ensure_env() {
  backup_dirs_init
  if [[ ! -f "$ENV_FILE" ]]; then
    info "未找到 deploy/.env，从模板复制并生成随机密钥"
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    write_env_value MYSQL_ROOT_PASSWORD "$(generate_alnum)" "$ENV_FILE"
    write_env_value SJHERP_DB_PASSWORD "$(generate_alnum)" "$ENV_FILE"
    write_env_value SJHERP_JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')" "$ENV_FILE"
    write_env_value SJHERP_ADMIN_PASSWORD "$(generate_admin_password)" "$ENV_FILE"
  else
    info "使用已有 deploy/.env"
    chmod 600 "$ENV_FILE" 2>/dev/null || true
    load_env_file "$ENV_FILE"
    local changed=0
    if is_placeholder_secret "${MYSQL_ROOT_PASSWORD:-}"; then
      write_env_value MYSQL_ROOT_PASSWORD "$(generate_alnum)" "$ENV_FILE"
      changed=1
    fi
    if is_placeholder_secret "${SJHERP_DB_PASSWORD:-}"; then
      write_env_value SJHERP_DB_PASSWORD "$(generate_alnum)" "$ENV_FILE"
      changed=1
    fi
    if is_placeholder_secret "${SJHERP_JWT_SECRET:-}"; then
      write_env_value SJHERP_JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')" "$ENV_FILE"
      changed=1
    fi
    if is_placeholder_secret "${SJHERP_ADMIN_PASSWORD:-}"; then
      write_env_value SJHERP_ADMIN_PASSWORD "$(generate_admin_password)" "$ENV_FILE"
      changed=1
    fi
    if [[ "$changed" -eq 1 ]]; then
      info "检测到示例密钥，已替换为随机值（写入 deploy/.env）"
    fi
  fi
  require_env_keys "$ENV_FILE"
  load_env_file "$ENV_FILE"
  if [[ ${#SJHERP_JWT_SECRET} -lt 32 ]]; then
    die "SJHERP_JWT_SECRET 太短（需要 ≥ 32 字节）。请编辑 deploy/.env"
  fi
  if [[ ${#SJHERP_ADMIN_PASSWORD} -lt 8 ]]; then
    die "SJHERP_ADMIN_PASSWORD 太短（≥ 8 位，须含大写、小写和数字）"
  fi
  if [[ ! "$SJHERP_ADMIN_PASSWORD" =~ [A-Z] || ! "$SJHERP_ADMIN_PASSWORD" =~ [a-z] || ! "$SJHERP_ADMIN_PASSWORD" =~ [0-9] ]]; then
    die "SJHERP_ADMIN_PASSWORD 须同时包含大写字母、小写字母和数字"
  fi
  ensure_tls_certs
}

print_success() {
  local url admin_password
  url="$(base_url)"
  admin_password="$(env_lookup SJHERP_ADMIN_PASSWORD "$ENV_FILE")"
  cat <<EOF

----------------------------------------
SJHERP 已启动
打开浏览器：${url}/
默认账号：admin
初始密码：${admin_password}
  （来自 deploy/.env；数据库里已有 admin 后，改 .env 不会改登录密码）

TLS 模式：$(tls_mode)（自签证书时浏览器会告警，换正式证书见 docs/部署-安全基线.md）
未配置 SJHERP_LLM_API_KEY 时，聊天走占位回复，传统工作台仍可用。
配置了第三方 LLM 后，聊天页会提示业务数据出境；可改本地模型或 SJHERP_LLM_REDACT。
大记忆默认关闭。请立刻挂上定时备份：见 docs/部署-备份恢复.md 与 deploy/crontab.example。
完整说明见 docs/部署-一键安装.md 与 docs/部署-安全基线.md
----------------------------------------
EOF
}

main() {
  assert_files
  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    check_assets
    exit 0
  fi
  install_prereqs
  install_docker
  ensure_env
  info "构建并启动 nginx / app / mysql / qdrant（首次编译后端可能需数分钟）"
  compose pull mysql qdrant || warn "预拉 mysql/qdrant 镜像失败，将在 up 时重试"
  compose up -d --build
  wait_http "$(base_url)/api/health" 240
  "$DEPLOY_DIR/smoke.sh" --login
  print_success
}

main
