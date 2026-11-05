# syntax=docker/dockerfile:1

# MediPortal (React + TypeScript + Vite frontend) - production image.
#
# Build from the repository root, with MediPortal/ as the build context:
#   docker build -f infrastructure/mediportal/Dockerfile -t medinexus-mediportal:local MediPortal
#
# The MediCore API URL is embedded at BUILD time: Vite replaces
# import.meta.env.VITE_API_BASE_URL in the JavaScript bundle during
# `npm run build`. Nginx only serves the finished files, so changing the URL
# requires a rebuild:
#   docker build ... --build-arg VITE_API_BASE_URL=http://localhost:8080 MediPortal
# The URL is used by the user's BROWSER, so it must be an address the browser
# can reach (e.g. http://localhost:8080), never a container name such as
# http://medicore:8080. It is public: never pass secrets as build arguments.

# ---- Stage 1: build the static files with Node.js ----
FROM node:24-alpine AS build
WORKDIR /app

# Dependencies first, so this layer is reused while only the sources change.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

# Sources (see MediPortal/.dockerignore: no node_modules, dist or .env files).
COPY . .

ARG VITE_API_BASE_URL=http://localhost:8080
RUN npm run build

# ---- Stage 2: serve dist/ with Nginx as a non-root user ----
# nginx-unprivileged is NGINX's official image variant that runs as user
# "nginx" (UID 101) and listens on an unprivileged port.
FROM nginxinc/nginx-unprivileged:stable-alpine AS runtime

COPY <<"EOF" /etc/nginx/conf.d/default.conf
server {
    listen 8080;
    server_name _;
    server_tokens off;

    root /usr/share/nginx/html;
    index index.html;

    # React Router uses browser history routing: paths without a matching
    # file (e.g. /patients/1) are frontend routes and get index.html.
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Vite's content-hashed build files never change: cache them long-term.
    # A missing file returns 404 instead of index.html.
    location /assets/ {
        try_files $uri =404;
        add_header Cache-Control "public, max-age=31536000, immutable";
    }

    # Always revalidate index.html so a new deployment is picked up.
    location = /index.html {
        add_header Cache-Control "no-cache";
    }
}
EOF

# Only the compiled frontend: no sources, node_modules or build tools.
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080
# USER (101) and CMD (nginx -g 'daemon off;') are inherited from the base image.
