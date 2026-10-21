# SJHERP 前端生产镜像：从 web/ 构建静态资源，由 nginx 提供并反代 /api
# 禁止使用 web-dist/ 预发包。
# syntax=docker/dockerfile:1

FROM node:22-alpine AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci
COPY web/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY --from=web-build /web/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=6 \
    CMD wget -qO- http://127.0.0.1/api/health || exit 1
