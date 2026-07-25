#!/usr/bin/env bash
# Arranque de desarrollo local (Linux/macOS).
# Uso:  ./scripts/dev-up.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -f "$ROOT/.env" ]; then
  cp "$ROOT/.env.example" "$ROOT/.env"
  echo "Creado .env desde .env.example. Rellena los secretos antes de usar IA real."
fi

cd "$ROOT"
docker compose up -d --build
echo "Servicios levantados."
echo "  API Gateway:      http://localhost:8080/health"
echo "  AI Orchestrator:  http://localhost:8090/health"
