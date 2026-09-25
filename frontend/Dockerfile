# ---- Stage "build" ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_SENTRY_DSN
ENV VITE_SENTRY_DSN=$VITE_SENTRY_DSN
RUN npm run build

# ---- Stage "runtime" ----
FROM nginx:alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
ENV NGINX_ENVSUBST_FILTER='^(SERVER_PORT|BACKEND_HOST)$'
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
