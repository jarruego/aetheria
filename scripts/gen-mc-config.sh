#!/usr/bin/env bash
# Genera la configuracion de runtime de la red Minecraft desde las plantillas.
# Inyecta el secreto de forwarding (desde .env) en archivos que quedan FUERA de git.
#
#   ./scripts/gen-mc-config.sh          # modo 'lean' (solo main, sin End) = cloud/defecto
#   ./scripts/gen-mc-config.sh full     # 'full' (lobby + main + End) = local completo
set -euo pipefail

MODE="${1:-lean}"
case "$MODE" in
  lean|full) ;;
  *) echo "ERROR: modo invalido '$MODE' (usa lean|full)" >&2; exit 1 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
[ -f "$ENV_FILE" ] || { echo "ERROR: .env no existe. Copia .env.example a .env primero." >&2; exit 1; }

SECRET="$(grep -E '^\s*VELOCITY_FORWARDING_SECRET\s*=' "$ENV_FILE" | head -n1 | sed -E 's/^\s*VELOCITY_FORWARDING_SECRET\s*=\s*//' | tr -d '\r')"
[ -n "$SECRET" ] || { echo "ERROR: VELOCITY_FORWARDING_SECRET no definido en .env" >&2; exit 1; }
if [ "$SECRET" = "changeme-velocity-modern-forwarding-secret" ]; then
  echo "AVISO: VELOCITY_FORWARDING_SECRET usa el valor por defecto. Cambialo en .env para produccion." >&2
fi

# Parametros por modo
if [ "$MODE" = "full" ]; then
  PAPER_SERVERS="lobby main creative"
  SERVERS='lobby = "lobby:25565"\nmain = "main:25565"\ncreative = "creative:25565"'
  TRY='["lobby"]'
  ALLOW_END='true'
else
  PAPER_SERVERS="main"
  SERVERS='main = "main:25565"'
  TRY='["main"]'
  ALLOW_END='false'
fi

GEN="$ROOT/minecraft/.generated"
# Los Paper montan el DIRECTORIO config completo (si montaramos un archivo suelto,
# Docker crearia /data/config como root y Paper no podria escribir sus otros configs).
mkdir -p "$GEN/velocity" "$GEN/lobby/plugins" "$GEN/main/plugins"
for s in $PAPER_SERVERS; do mkdir -p "$GEN/$s/config"; done

# velocity.toml (sin secreto; servidores/try segun modo) + forwarding.secret
sed -e "s|%%SERVERS%%|$SERVERS|g" -e "s|%%TRY%%|$TRY|g" \
    "$ROOT/minecraft/proxy-velocity/velocity.toml.template" > "$GEN/velocity/velocity.toml"
printf '%s' "$SECRET" > "$GEN/velocity/forwarding.secret"

# config/paper-global.yml para cada servidor Paper (con el secreto inline)
for s in $PAPER_SERVERS; do
  sed "s|%%FORWARDING_SECRET%%|$SECRET|g" "$ROOT/minecraft/paper-global.yml.template" > "$GEN/$s/config/paper-global.yml"
done

# bukkit.yml de 'main' (End segun modo)
sed "s|%%ALLOW_END%%|$ALLOW_END|g" "$ROOT/minecraft/bukkit.yml.template" > "$GEN/main/bukkit.yml"

echo "Config de Minecraft generada (modo: $MODE) en minecraft/.generated/."
