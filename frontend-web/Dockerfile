# =============================================================================
# ShopPilot AI —— frontend-web 镜像（多阶段构建）
# =============================================================================
# 依据：
#   PROJECT_STRUCTURE.md §6 / §7.1 —— frontend-web 容器内 80，仅经 nginx 访问（不直接暴露）
#   DEVELOPMENT_PLAN     M1-T08    —— `npm ci && npm run build`
#
# 构建：docker build -t shoppilot/frontend-web:0.1.0 frontend-web
# =============================================================================

# ---------- 构建阶段：Vite 产出 dist ----------
FROM node:22-alpine AS build
WORKDIR /app
# 先复制清单，命中 npm 层缓存
COPY package.json package-lock.json ./
# 加固：显式指定与 package-lock.json 一致的镜像源，并放大重试/超时，
# 规避 CI/本地网络抖动导致的 EIDLETIMEOUT（`npm ci` 默认仅重试 2 次、超时 5 分钟）
RUN npm ci --no-audit --no-fund \
        --registry=https://registry.npmmirror.com \
        --fetch-retries=5 \
        --fetch-retry-mintimeout=20000 \
        --fetch-retry-maxtimeout=180000 \
        --fetch-timeout=900000
COPY . .
RUN npm run build

# ---------- 运行阶段：静态托管 ----------
FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
# SPA fallback（history 模式深链回落 index.html）；见 nginx.conf 头部说明
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
