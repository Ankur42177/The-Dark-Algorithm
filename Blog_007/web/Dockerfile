# syntax=docker/dockerfile:1
# =====================================================================
# Pulse web (Next.js standalone output). Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
#   docker build -t pulse/web:0.1.0 --build-arg NEXT_PUBLIC_API_URL=http://localhost:30081 web/
# Three stages: deps (npm ci, cached until package-lock changes) → build → tiny runtime.
# =====================================================================
ARG NODE_IMAGE=node:24.22.3-alpine

FROM ${NODE_IMAGE} AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# Baked into the BROWSER bundle at build time (it's public anyway).
ARG NEXT_PUBLIC_API_URL=http://localhost:30081
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL} NEXT_TELEMETRY_DISABLED=1
# No backend during `docker build`: pages are built empty and filled at runtime (revalidate / webhook).
ENV PULSE_API_URL=http://backend-not-available-at-build:1
RUN mkdir -p public && npm run build

FROM ${NODE_IMAGE} AS runtime
LABEL org.opencontainers.image.title="pulse-web" \
      org.opencontainers.image.vendor="Ravenclaw_007 LLC" \
      org.opencontainers.image.authors="Ankur Kumar" \
      org.opencontainers.image.source="https://github.com/Ankur42177/The-Dark-Algorithm"
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1
WORKDIR /app
RUN addgroup -S -g 10001 pulse && adduser -S -u 10001 -G pulse pulse
COPY --from=build --chown=10001:10001 /app/.next/standalone ./
COPY --from=build --chown=10001:10001 /app/.next/static ./.next/static
COPY --from=build --chown=10001:10001 /app/public ./public
USER 10001
EXPOSE 3000
CMD ["node", "server.js"]
