# Reverse proxy image: the five static frontends plus nginx. No Node.js in the runtime image (ADR-0012).
# Build context is the repository root.
FROM node:24 AS web
RUN corepack enable
WORKDIR /src/frontend
COPY frontend/ ./
RUN pnpm install --frozen-lockfile && pnpm build

FROM nginx:1.27-alpine
COPY deploy/proxy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=web /src/frontend/apps/admin/out /usr/share/nginx/html/admin
COPY --from=web /src/frontend/apps/console/out /usr/share/nginx/html/console
COPY --from=web /src/frontend/apps/kiosk/out /usr/share/nginx/html/kiosk
COPY --from=web /src/frontend/apps/display/out /usr/share/nginx/html/display
COPY --from=web /src/frontend/apps/visitor/out /usr/share/nginx/html/visitor
