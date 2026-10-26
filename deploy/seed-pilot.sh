#!/usr/bin/env bash
# SJHERP 种子试点（M8-T07）：语法/资产 dry-run，或登录后经 REST 装入通润驱动。
# 文档：docs/部署-种子试点.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0
PACK="sunshine"

usage() {
  cat <<'EOF'
SJHERP 种子试点（默认通润驱动；qinghe 为备选演示数据集）

用法：
  ./deploy/seed-pilot.sh --check              不需要 Docker：脚本语法 + 种子资产守门
  ./deploy/seed-pilot.sh                      对运行中的 compose 用 admin 装 sunshine 包
  ./deploy/seed-pilot.sh --pack qinghe        装第二套种子包（须空库；与 sunshine 互斥）
  ./deploy/seed-pilot.sh --help

约束：
  生产默认不要开 SJHERP_DEMO_SEED。本脚本走 REST，需 ADMIN/BOSS。
  不改 web-dist/，不关账，不绕过单据状态机。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --pack)
      shift
      PACK="${1:-}"
      [[ -n "$PACK" ]] || die "--pack 需要 sunshine 或 qinghe"
      ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$1 （--help 查看用法）" ;;
  esac
  shift
done

PACK="$(printf '%s' "$PACK" | tr '[:upper:]' '[:lower:]')"
[[ "$PACK" == "sunshine" || "$PACK" == "qinghe" ]] || die "未知种子包：$PACK（支持 sunshine / qinghe）"

assert_seed_files() {
  [[ -f "$DEPLOY_DIR/seed-pilot.sh" ]] || die "缺少 deploy/seed-pilot.sh"
  [[ -f "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/pilot/DemoSeedService.java" ]] \
    || die "缺少 DemoSeedService.java"
  [[ -f "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/pilot/DemoCompanyPack.java" ]] \
    || die "缺少 DemoCompanyPack.java"
  [[ -f "$REPO_ROOT/server/sjherp-app/src/main/resources/application.yml" ]] \
    || die "缺少 application.yml"
  [[ -f "$REPO_ROOT/docs/测试-M8-T07种子用户试点.md" ]] || die "缺少 docs/测试-M8-T07种子用户试点.md"
}

run_check() {
  info "校验种子试点资产"
  assert_seed_files
  bash -n "$DEPLOY_DIR/seed-pilot.sh"
  local yml pack_java controller
  yml="$(cat "$REPO_ROOT/server/sjherp-app/src/main/resources/application.yml")"
  grep -Fq 'enabled: ${SJHERP_DEMO_SEED:false}' <<<"$yml" \
    || die "application.yml 必须默认关闭 sjherp.demo-seed.enabled"
  pack_java="$(cat "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/pilot/DemoCompanyPack.java")"
  grep -Fq '"通润驱动"' <<<"$pack_java" || die "sunshine 包缺少通润驱动"
  grep -Fq '"演示青禾食品"' <<<"$pack_java" || die "qinghe 包缺少演示青禾食品"
  grep -Fq 'SJHERP-DEMO-SEED:' <<<"$pack_java" \
    || grep -Fq 'seedMarker' <<<"$pack_java" \
    || die "种子包须有 seedMarker"
  controller="$(cat "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/pilot/DemoSeedController.java")"
  grep -Fq '/api/pilot/demo-seed' <<<"$controller" || die "缺少 REST /api/pilot/demo-seed"
  grep -Fq "pilot:seed" <<<"$controller" || die "REST 须守门 pilot:seed"
  local seeder
  seeder="$(cat "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/pilot/DemoSeedService.java")"
  grep -F 'periodCloseService.close' <<<"$seeder" && die "种子编排禁止调用关账" || true
  if [[ -d "$REPO_ROOT/.git" ]] && command -v git >/dev/null 2>&1; then
    if git -C "$REPO_ROOT" diff --stat -- web-dist | grep -q .; then
      die "本工作区 web-dist/ 有改动（M8-T07 NO_WEB_DIST）"
    fi
  fi
  info "种子 --check 通过（默认 sunshine/通润驱动，启动开关默认 false）"
}

extract_json_field() {
  local file="$1"
  local field="$2"
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2],"") or "")' \
      "$file" "$field"
    return 0
  fi
  grep -oE "\"${field}\"[[:space:]]*:[[:space:]]*\"[^\"]+\"" "$file" | head -n 1 \
    | sed -E "s/.*:[[:space:]]*\"([^\"]+)\".*/\1/"
}

run_live() {
  need_cmd curl
  [[ -f "$ENV_FILE" ]] || die "装种子需要 deploy/.env（先 ./deploy/install.sh）"
  ADMIN_PASSWORD="$(env_lookup SJHERP_ADMIN_PASSWORD "$ENV_FILE")"
  [[ -n "${ADMIN_PASSWORD:-}" ]] || die "deploy/.env 缺少 SJHERP_ADMIN_PASSWORD"
  if [[ "$ADMIN_PASSWORD" == *'"'* || "$ADMIN_PASSWORD" == *$'\n'* ]]; then
    die "SJHERP_ADMIN_PASSWORD 含有不支持的字符（请不要用引号或换行）"
  fi
  local url login_body http_code token seed_body
  url="$(base_url)"
  # shellcheck disable=SC2206
  local curl_tls=()
  if [[ "$url" == https://* ]]; then
    curl_tls=(-k)
  fi
  info "管理员登录后装载试点种子 pack=$PACK"
  login_body="$(mktemp)"
  http_code="$(curl -sS --max-time 20 "${curl_tls[@]}" -o "$login_body" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PASSWORD}\"}" \
    "$url/api/auth/login" || true)"
  if [[ "$http_code" != "200" ]]; then
    warn "登录响应：$(cat "$login_body")"
    rm -f "$login_body"
    die "管理员登录失败（HTTP $http_code）"
  fi
  token="$(extract_json_field "$login_body" token)"
  rm -f "$login_body"
  [[ -n "$token" ]] || die "登录成功但没有 token"
  seed_body="$(mktemp)"
  http_code="$(curl -sS --max-time 60 "${curl_tls[@]}" -o "$seed_body" -w '%{http_code}' \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"pack\":\"${PACK}\"}" \
    "$url/api/pilot/demo-seed" || true)"
  if [[ "$http_code" != "200" ]]; then
    warn "装载响应：$(cat "$seed_body")"
    rm -f "$seed_body"
    die "种子装载失败（HTTP $http_code）。409 通常是已装另一包或账期已关"
  fi
  local company period closeable
  company="$(extract_json_field "$seed_body" companyLabel)"
  period="$(extract_json_field "$seed_body" period)"
  closeable="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("periodCloseable"))' "$seed_body" 2>/dev/null || true)"
  info "已装载 ${company:-?} 账期=${period:-?} closeable=${closeable:-?}（账期仍 OPEN，请按测试文档跑关账清单）"
  cat "$seed_body"
  printf '\n'
  rm -f "$seed_body"
}

if [[ "$CHECK_ONLY" -eq 1 ]]; then
  run_check
  exit 0
fi

run_live
