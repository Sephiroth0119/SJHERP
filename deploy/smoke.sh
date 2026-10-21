#!/usr/bin/env bash
# SJHERP 安装后冒烟：登录页 + /api/health，可选登录探活。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  cat <<'EOF'
SJHERP 冒烟检查

用法：
  ./deploy/smoke.sh           检查登录页与 /api/health
  ./deploy/smoke.sh --login   额外用 .env 中的 admin 密码打登录接口
  ./deploy/smoke.sh --help
EOF
}

DO_LOGIN=0
for arg in "$@"; do
  case "$arg" in
    --login) DO_LOGIN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$arg （--help 查看用法）" ;;
  esac
done

need_cmd curl
URL="$(base_url)"

info "检查登录页 $URL/"
PAGE="$(curl -fsS --max-time 10 "$URL/")"
if ! grep -q 'SJHERP' <<<"$PAGE"; then
  die "登录页未包含 SJHERP 标题，请看 nginx 是否在提供前端静态资源"
fi
info "登录页正常"

info "检查健康接口 $URL/api/health"
HEALTH="$(curl -fsS --max-time 10 "$URL/api/health")"
if ! grep -q '"status":"UP"' <<<"$HEALTH" && ! grep -q '"status": "UP"' <<<"$HEALTH"; then
  die "健康检查未返回 UP：$HEALTH"
fi
info "健康检查正常：$HEALTH"

if [[ "$DO_LOGIN" -eq 1 ]]; then
  [[ -f "$ENV_FILE" ]] || die "--login 需要 deploy/.env"
  ADMIN_PASSWORD="$(env_lookup SJHERP_ADMIN_PASSWORD "$ENV_FILE")"
  [[ -n "${ADMIN_PASSWORD:-}" ]] || die "deploy/.env 缺少 SJHERP_ADMIN_PASSWORD"
  if [[ "$ADMIN_PASSWORD" == *'"'* || "$ADMIN_PASSWORD" == *$'\n'* ]]; then
    die "SJHERP_ADMIN_PASSWORD 含有不支持的字符（请不要用引号或换行）"
  fi
  info "尝试管理员登录（不打印令牌）"
  LOGIN_BODY="$(mktemp)"
  HTTP_CODE="$(curl -sS --max-time 15 -o "$LOGIN_BODY" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PASSWORD}\"}" \
    "$URL/api/auth/login" || true)"
  if [[ "$HTTP_CODE" != "200" ]]; then
    warn "登录响应：$(cat "$LOGIN_BODY")"
    rm -f "$LOGIN_BODY"
    die "管理员登录失败（HTTP $HTTP_CODE）。密码见 deploy/.env 的 SJHERP_ADMIN_PASSWORD"
  fi
  if ! grep -q '"token"' "$LOGIN_BODY"; then
    rm -f "$LOGIN_BODY"
    die "登录成功但响应没有 token，请检查 /api/auth/login"
  fi
  rm -f "$LOGIN_BODY"
  info "管理员登录正常（用户名 admin）"
fi

info "冒烟通过"
printf '打开浏览器访问：%s\n' "$URL/"
printf '默认账号：admin （密码在 deploy/.env 的 SJHERP_ADMIN_PASSWORD，仅首次启动生效）\n'
