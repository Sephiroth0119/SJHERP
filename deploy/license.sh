#!/usr/bin/env bash
# SJHERP 离线许可（M8-T08）：检查样例、解析 JSON、用测试密钥签发。
# 文档：docs/部署-许可.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CHECK_ONLY=0
ISSUE_ONLY=0
VERIFY_ONLY=0
SUBJECT=""
EXPIRES=""
SEATS=""
MODULES=""
GRACE="14"
KID="dev-2026"
ISSUED=""
OUT=""
FILE=""
KEY="$DEPLOY_DIR/license/dev-ed25519-private.pem"
PUBLIC="$DEPLOY_DIR/license/dev-ed25519-public.pem"

usage() {
  cat <<'EOF'
SJHERP 离线许可（JSON + Ed25519，私有部署骨架）

用法：
  ./deploy/license.sh --check     不需要 Docker：脚本语法 + 样例解析/验签
  ./deploy/license.sh --verify [--file path]
                                  解析并验签（默认样例 sample.license.json）
  ./deploy/license.sh --issue --subject 名称 [--expires YYYY-MM-DD] [--seats N]
                                  [--modules a,b] [--out path]
  ./deploy/license.sh --help

约束：
  未配置 license 不挡业务。硬阻断仅 SJHERP_LICENSE_ENFORCE=true。
  本仓库测试私钥只用于样例；生产私钥不入库。
  不改 web-dist/，不接 Stripe/微信支付。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --issue) ISSUE_ONLY=1 ;;
    --verify) VERIFY_ONLY=1 ;;
    --subject) shift; SUBJECT="${1:-}" ;;
    --expires) shift; EXPIRES="${1:-}" ;;
    --seats) shift; SEATS="${1:-}" ;;
    --modules) shift; MODULES="${1:-}" ;;
    --grace) shift; GRACE="${1:-}" ;;
    --kid) shift; KID="${1:-}" ;;
    --issued) shift; ISSUED="${1:-}" ;;
    --out) shift; OUT="${1:-}" ;;
    --file) shift; FILE="${1:-}" ;;
    --key) shift; KEY="${1:-}" ;;
    --public) shift; PUBLIC="${1:-}" ;;
    --help|-h) usage; exit 0 ;;
    *) die "未知参数：$1 （--help 查看用法）" ;;
  esac
  shift
done

LICENSE_DIR="$DEPLOY_DIR/license"
SAMPLE="$LICENSE_DIR/sample.license.json"

canonical_text() {
  local subject="$1" issued="$2" expires="$3" seats="$4" modules="$5" grace="$6" kid="$7"
  printf '%s\n' 'SJHERP-LICENSE-V1'
  printf '%s\n' 'v=1'
  printf '%s\n' "subject=${subject}"
  printf '%s\n' "issuedAt=${issued}"
  printf '%s\n' "expiresAt=${expires}"
  printf '%s\n' "seats=${seats}"
  printf '%s\n' "modules=${modules}"
  printf '%s\n' "graceDays=${grace}"
  printf '%s\n' "kid=${kid}"
}

sort_modules() {
  local raw="${1:-}"
  if [[ -z "$raw" ]]; then
    printf ''
    return 0
  fi
  python3 -c 'import sys; print(",".join(sorted({p.strip() for p in sys.argv[1].split(",") if p.strip()})))' "$raw"
}

json_field() {
  python3 - "$1" "$2" <<'PY'
import json, sys
path, field = sys.argv[1], sys.argv[2]
doc = json.load(open(path, encoding="utf-8"))
value = doc.get(field)
if value is None:
    print("")
elif isinstance(value, list):
    print(",".join(str(item) for item in value))
else:
    print(value)
PY
}

verify_file() {
  local file="$1"
  [[ -f "$file" ]] || die "找不到 license 文件：$file"
  python3 - "$file" <<'PY' >/dev/null
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
for key in ("v", "subject", "issuedAt", "sig"):
    if key not in doc or doc[key] in (None, ""):
        raise SystemExit(f"缺少字段 {key}")
if doc.get("v") != 1:
    raise SystemExit("仅支持 v=1")
PY
  local subject issued expires seats modules grace kid sig
  subject="$(json_field "$file" subject)"
  issued="$(json_field "$file" issuedAt)"
  expires="$(json_field "$file" expiresAt)"
  seats="$(json_field "$file" seats)"
  modules="$(sort_modules "$(json_field "$file" modules)")"
  grace="$(json_field "$file" graceDays)"
  kid="$(json_field "$file" kid)"
  sig="$(json_field "$file" sig)"
  local canon sigbin
  canon="$(mktemp)"
  sigbin="$(mktemp)"
  canonical_text "$subject" "$issued" "$expires" "$seats" "$modules" "$grace" "$kid" > "$canon"
  python3 - "$sig" "$sigbin" <<'PY'
import base64, sys, pathlib
raw = sys.argv[1] + "=" * ((4 - len(sys.argv[1]) % 4) % 4)
pathlib.Path(sys.argv[2]).write_bytes(base64.urlsafe_b64decode(raw.encode()))
PY
  openssl pkeyutl -verify -pubin -inkey "$PUBLIC" -rawin -in "$canon" -sigfile "$sigbin" >/dev/null
  rm -f "$canon" "$sigbin"
  info "license 解析并验签通过：$file（subject=$subject issuedAt=$issued expiresAt=${expires:-∞}）"
}

assert_license_files() {
  [[ -f "$DEPLOY_DIR/license.sh" ]] || die "缺少 deploy/license.sh"
  [[ -f "$SAMPLE" ]] || die "缺少 $SAMPLE"
  [[ -f "$PUBLIC" ]] || die "缺少测试公钥"
  [[ -f "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/license/LicenseEvaluator.java" ]] \
    || die "缺少 LicenseEvaluator.java"
  [[ -f "$REPO_ROOT/docs/测试-M8-T08许可与计费.md" ]] || die "缺少 docs/测试-M8-T08许可与计费.md"
  [[ -f "$REPO_ROOT/docs/部署-许可.md" ]] || die "缺少 docs/部署-许可.md"
}

run_check() {
  info "校验离线许可资产"
  need_cmd python3
  need_cmd openssl
  assert_license_files
  bash -n "$DEPLOY_DIR/license.sh"
  local yml envex controller
  yml="$(cat "$REPO_ROOT/server/sjherp-app/src/main/resources/application.yml")"
  grep -Fq 'enforce: ${SJHERP_LICENSE_ENFORCE:false}' <<<"$yml" \
    || die "application.yml 必须默认关闭 sjherp.license.enforce"
  grep -Fq 'path: ${SJHERP_LICENSE_PATH:}' <<<"$yml" \
    || die "application.yml 必须允许空 SJHERP_LICENSE_PATH"
  envex="$(cat "$ENV_EXAMPLE")"
  grep -Fq 'SJHERP_LICENSE_ENFORCE=false' <<<"$envex" || die ".env.example 须默认关闭硬阻断"
  grep -Fq 'SJHERP_LICENSE_PATH=' <<<"$envex" || die ".env.example 缺少 SJHERP_LICENSE_PATH"
  grep -Fq 'SJHERP_LICENSE_PUBLIC_KEY=' <<<"$envex" || die ".env.example 缺少公钥变量"
  controller="$(cat "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/license/LicenseController.java")"
  grep -Fq '/api/license' <<<"$controller" || die "缺少 REST /api/license"
  grep -Fq "license:view" <<<"$controller" || die "REST 须守门 license:view"
  grep -E 'Stripe|微信支付|subscribe' "$REPO_ROOT/server/sjherp-app/src/main/java/com/sjherp/app/license"/*.java \
    && die "许可骨架禁止接入支付 SDK" || true
  verify_file "$SAMPLE"
  if [[ -d "$REPO_ROOT/.git" ]] && command -v git >/dev/null 2>&1; then
    if git -C "$REPO_ROOT" diff --stat -- web-dist | grep -q .; then
      die "本工作区 web-dist/ 有改动（M8-T08 NO_WEB_DIST）"
    fi
  fi
  info "许可 --check 通过（样例可解析验签，硬阻断默认关闭）"
}

run_issue() {
  [[ -n "$SUBJECT" ]] || die "--issue 需要 --subject"
  [[ -f "$KEY" ]] || die "找不到私钥 $KEY"
  need_cmd openssl
  need_cmd python3
  OUT="${OUT:-$LICENSE_DIR/sjherp.license.json}"
  ISSUED="${ISSUED:-$(date -u +%F)}"
  local modules
  modules="$(sort_modules "$MODULES")"
  local canon sig
  canon="$(mktemp)"
  canonical_text "$SUBJECT" "$ISSUED" "$EXPIRES" "$SEATS" "$modules" "$GRACE" "$KID" > "$canon"
  sig="$(openssl pkeyutl -sign -inkey "$KEY" -rawin -in "$canon" | python3 -c 'import sys,base64; print(base64.urlsafe_b64encode(sys.stdin.buffer.read()).rstrip(b"=").decode())')"
  rm -f "$canon"
  python3 - "$OUT" "$SUBJECT" "$ISSUED" "$EXPIRES" "$SEATS" "$modules" "$GRACE" "$KID" "$sig" <<'PY'
import json, sys, pathlib
out, subject, issued, expires, seats, modules, grace, kid, sig = sys.argv[1:]
doc = {"v": 1, "subject": subject, "issuedAt": issued, "sig": sig}
if expires:
    doc["expiresAt"] = expires
if seats:
    doc["seats"] = int(seats)
if modules:
    doc["modules"] = [item for item in modules.split(",") if item]
if grace:
    doc["graceDays"] = int(grace)
if kid:
    doc["kid"] = kid
path = pathlib.Path(out)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(out)
PY
  verify_file "$OUT"
}

if [[ "$CHECK_ONLY" -eq 1 ]]; then
  run_check
  exit 0
fi

if [[ "$ISSUE_ONLY" -eq 1 ]]; then
  run_issue
  exit 0
fi

if [[ "$VERIFY_ONLY" -eq 1 ]]; then
  verify_file "${FILE:-$SAMPLE}"
  exit 0
fi

usage
die "请指定 --check、--verify 或 --issue"
