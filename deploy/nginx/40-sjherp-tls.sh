#!/bin/sh
# nginx 官方镜像会执行 /docker-entrypoint.d/*.sh。按 SJHERP_TLS_MODE 选择配置。
set -eu

MODE="${SJHERP_TLS_MODE:-self-signed}"
case "$MODE" in
  off|OFF|http|HTTP)
    echo "SJHERP TLS: off（仅 HTTP :80）"
    cp /etc/nginx/nginx-http.conf /etc/nginx/nginx.conf
    ;;
  self-signed|SELF-SIGNED|custom|CUSTOM|letsencrypt|LETSENCRYPT)
    if [ ! -f /etc/nginx/certs/fullchain.pem ] || [ ! -f /etc/nginx/certs/privkey.pem ]; then
      echo "SJHERP TLS: 已启用（$MODE）但缺少 /etc/nginx/certs/fullchain.pem 或 privkey.pem" >&2
      echo "请先运行 ./deploy/install.sh（会生成自签证书），或放入 Let's Encrypt 证书。" >&2
      exit 1
    fi
    echo "SJHERP TLS: $MODE（HTTPS :443，HTTP 除 /api/health 外重定向）"
    cp /etc/nginx/nginx-tls.conf /etc/nginx/nginx.conf
    ;;
  *)
    echo "未知 SJHERP_TLS_MODE=$MODE（允许：off / self-signed / custom）" >&2
    exit 1
    ;;
esac
