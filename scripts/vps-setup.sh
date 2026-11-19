#!/usr/bin/env bash
set -euo pipefail

# Bootstrap Ubuntu 24.04 VPS for ProphecyVerse (Node.js 20 + PM2 + Nginx)
if [ "$(id -u)" -ne 0 ]; then
  echo "Run this script with sudo or as root." >&2
  exit 1
fi

APP_DIR="${1:-/var/www/prophecyverse}"

apt-get update -y
apt-get install -y curl git nginx ca-certificates gnupg

if ! command -v node >/dev/null 2>&1 || [ "$(node -v | cut -d. -f1 | tr -d v)" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi

npm install -g pm2

mkdir -p "${APP_DIR}"
echo "VPS prerequisites installed."
echo "Next steps:"
echo "  1. Copy .env.example to ${APP_DIR}/.env and configure PORT, CORS_ORIGIN, and authorized MovieBox server credentials."
echo "  2. Copy nginx/prophecyverse.conf.template to /etc/nginx/sites-available/prophecyverse, set YOUR_DOMAIN, symlink to sites-enabled, and reload nginx."
echo "  3. Build and start with: npm ci && npm run build && pm2 startOrReload ecosystem.config.cjs --update-env && pm2 save"
