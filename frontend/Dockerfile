# 前端镜像：Node 构建静态产物 → Nginx 托管并反向代理后端。
#
# 为什么要反代（而不是让浏览器直连后端的 8080/8090/18080）：
#   浏览器只看到一个 origin，CORS、TLS 证书、安全头都只需处理一份；
#   WebSocket 也从同一 origin 的 /ws 转给后端的独立监听器（DECISIONS.md D-37）。
#
# 为什么构建期要 VITE_WS_SAME_ORIGIN=1：
#   同源部署下浏览器不能去连 ticket 里的 wsPort（18080）——部署拓扑并不对外暴露它，
#   拼上去的表现是“前端一直重连、后端日志一切正常”。详见 frontend/src/realtime/socket.ts。
#   HTTP 侧不需要额外设置：API 基址默认就是相对的 `/api/v1`（见 src/api/client.ts 与 App.vue），
#   本来就落在同一 origin，被这里的 Nginx 转到后端。
#
# 构建（也可 `docker compose build frontend`）：
#   docker build -t bid-arena-frontend ./frontend
#
# 两个基础镜像用 ARG 暴露：网络受限（如国内或离线）时可用本地镜像源覆盖，而不必改本文件：
#   docker build --build-arg NODE_IMAGE=<镜像源>/node:22-alpine \
#                --build-arg NGINX_IMAGE=<镜像源>/nginx:1.27-alpine -t bid-arena-frontend ./frontend

ARG NODE_IMAGE=node:22-alpine
ARG NGINX_IMAGE=nginx:1.27-alpine

# ---------------------------------------------------------------------------
# 阶段 1：构建静态产物
# ---------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS build
WORKDIR /app

# 先只复制清单并安装依赖，让“依赖未变”时这一层能命中缓存。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# 单 origin 部署的默认值；本机开发不走这个镜像，不受影响。
ARG VITE_WS_SAME_ORIGIN=1
ENV VITE_WS_SAME_ORIGIN=$VITE_WS_SAME_ORIGIN
RUN npm run build

# ---------------------------------------------------------------------------
# 阶段 2：运行（只有 Nginx 与静态产物，没有 Node、没有 node_modules、没有源码）
# ---------------------------------------------------------------------------
FROM ${NGINX_IMAGE}
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
