FROM node:20-alpine as build-stage

WORKDIR /app
RUN corepack enable
RUN corepack prepare pnpm@latest --activate

RUN npm config set registry https://registry.npmmirror.com

COPY .npmrc package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

FROM nginx:stable-alpine as production-stage

# 静态产物
COPY --from=build-stage /app/dist /usr/share/nginx/html

# 反向代理模板：官方镜像启动时会对 /etc/nginx/templates/*.template 执行 envsubst，
# 把 ${BACKEND_URL} 替换为运行时环境变量（默认指向宿主机上的网关 8080）。
# 不装这个模板的话，容器里的页面能打开但 /api、/auth 全部 404。
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
ENV BACKEND_URL=http://host.docker.internal:8080

# 移除镜像自带的 default.conf，避免与上面的 server 块抢占 80 端口默认虚拟主机
RUN rm -f /etc/nginx/conf.d/default.conf
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]