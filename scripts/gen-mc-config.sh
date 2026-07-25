#!/usr/bin/env bash
# Genera la configuracion de runtime de la red Minecraft desde las plantillas.
# Inyecta el secreto de forwarding (desde .env) en archivos que quedan FUERA de git.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
[ -f "$ENV_FILE" ] || { echo "ERROR: .env no existe. Copia .env.example a .env primero." >&2; exit 1; }

SECRET="$(grep -E '^\s*VELOCITY_FORWARDING_SECRET\s*=' "$ENV_FILE" | head -n1 | sed -E 's/^\s*VELOCITY_FORWARDING_SECRET\s*=\s*//' | tr -d '\r')"
[ -n "$SECRET" ] || { echo "ERROR: VELOCITY_FORWARDING_SECRET no definido en .env" >&2; exit 1; }
if [ "$SECRET" = "changeme-velocity-modern-forwarding-secret" ]; then
  echo "AVISO: VELOCITY_FORWARDING_SECRET usa el valor por defecto. Cambialo en .env para produccion." >&2
fi

GEN="$ROOT/minecraft/.generated"
# Los Paper montan el DIRECTORIO config completo (si montaramos un archivo suelto,
# Docker crearia /data/config como root y Paper no podria escribir sus otros configs).
mkdir -p "$GEN/velocity" "$GEN/lobby/config" "$GEN/main/config"

# velocity.toml (sin secreto) + forwarding.secret
cp "$ROOT/minecraft/proxy-velocity/velocity.toml" "$GEN/velocity/velocity.toml"
printf '%s' "$SECRET" > "$GEN/velocity/forwarding.secret"

# config/paper-global.yml para lobby y main (con el secreto inline)
for s in lobby main; do
  sed "s|%%FORWARDING_SECRET%%|$SECRET|g" "$ROOT/minecraft/paper-global.yml.template" > "$GEN/$s/config/paper-global.yml"
done

echo "Config de Minecraft generada en minecraft/.generated/ (velocity, lobby, main)."
