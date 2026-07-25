#!/usr/bin/env bash
# =====================================================================
# Aetheria AI - Subida de secretos EXTERNOS a la maquina desplegada
#
# Los secretos de infraestructura (forwarding de Velocity, contrasena de
# Postgres, token interno) se generan solos en la maquina durante el
# cloud-init y nunca salen de ella. Este script existe solo para los
# secretos que NO se pueden generar: la API key del proveedor LLM.
#
# Por que scp y no una variable de Terraform:
#   - Cualquier valor que pase por Terraform queda EN CLARO en el
#     terraform.tfstate, aunque la variable este marcada sensitive.
#   - Ademas, inyectarlo por metadata lo deja legible en
#     http://169.254.169.254/opc/v2/instance/ para CUALQUIER proceso o
#     contenedor de la maquina. Una API key de pago no merece eso.
#   - scp la entrega una sola vez, cifrada, y aterriza en un .env con
#     permisos 600. Coste: un comando manual tras el apply.
#
# Uso:
#   export ANTHROPIC_API_KEY=sk-ant-...
#   ./upload-secrets.sh <IP_PUBLICA>
#
# La clave NUNCA se pasa como argumento (seria visible en `ps` y en el
# historial del shell): viaja en un fichero temporal con permisos 600.
# =====================================================================
set -euo pipefail

HOST_IP="${1:-}"
SSH_USER="${SSH_USER:-ubuntu}"
APP_DIR="${APP_DIR:-/opt/aetheria}"

if [ -z "$HOST_IP" ]; then
  echo "Uso: ANTHROPIC_API_KEY=sk-... $0 <IP_PUBLICA>" >&2
  echo "     (la IP la da 'terraform output public_ip')" >&2
  exit 1
fi

if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -z "${OPENAI_API_KEY:-}" ]; then
  echo "ERROR: exporta ANTHROPIC_API_KEY (o OPENAI_API_KEY) antes de ejecutar." >&2
  exit 1
fi

TMP="$(mktemp)"
chmod 600 "$TMP"
trap 'rm -f "$TMP"' EXIT

{
  printf 'ANTHROPIC_API_KEY=%s\n' "${ANTHROPIC_API_KEY:-}"
  printf 'OPENAI_API_KEY=%s\n' "${OPENAI_API_KEY:-}"
} > "$TMP"

echo "==> Subiendo secretos a $SSH_USER@$HOST_IP"
scp -q "$TMP" "$SSH_USER@$HOST_IP:/tmp/aetheria-secrets"

echo "==> Fusionando en $APP_DIR/.env y reiniciando el orchestrator"
ssh "$SSH_USER@$HOST_IP" "sudo bash -s" <<REMOTE
set -euo pipefail
APP_DIR="$APP_DIR"

ANTHROPIC="\$(sed -n 's/^ANTHROPIC_API_KEY=//p' /tmp/aetheria-secrets)"
OPENAI="\$(sed -n 's/^OPENAI_API_KEY=//p' /tmp/aetheria-secrets)"
shred -u /tmp/aetheria-secrets 2>/dev/null || rm -f /tmp/aetheria-secrets

sed -i "s|^ANTHROPIC_API_KEY=.*|ANTHROPIC_API_KEY=\$ANTHROPIC|" "\$APP_DIR/.env"
sed -i "s|^OPENAI_API_KEY=.*|OPENAI_API_KEY=\$OPENAI|" "\$APP_DIR/.env"
chmod 600 "\$APP_DIR/.env"

cd "\$APP_DIR"
docker compose up -d ai-orchestrator api-gateway
echo "OK: .env actualizado y servicios recargados."
REMOTE

echo "==> Listo. La clave no ha quedado en el tfstate ni en la metadata de OCI."
