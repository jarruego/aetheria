#!/usr/bin/env bash
# Arranque LOCAL COMPLETO (Linux/macOS): lobby + main + End + mas memoria.
# Genera la config en modo 'full' y levanta con el override docker-compose.full.yml.
# Uso:  ./scripts/dev-up-full.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -f "$ROOT/.env" ]; then
  cp "$ROOT/.env.example" "$ROOT/.env"
  echo "Creado .env desde .env.example. Rellena los secretos antes de usar IA real."
fi

# Config de Minecraft en modo FULL (lobby en velocity.toml, End activado).
"$ROOT/scripts/gen-mc-config.sh" full

cd "$ROOT"
docker compose -f docker-compose.yml -f docker-compose.full.yml up -d --build
echo "Sistema COMPLETO levantado (lobby + main + End)."
echo "  API Gateway:      http://localhost:8080/health"
echo "  AI Orchestrator:  http://localhost:8090/health"
echo "  World-State:      http://localhost:8070/health"
echo "  Minecraft Java:   localhost:${VELOCITY_PORT:-25565}  (entras al Lobby)"
echo "  Bedrock:          localhost:${GEYSER_BEDROCK_PORT:-19132}"
echo "  En el juego:  /server main   |   /server lobby"
