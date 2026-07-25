#!/usr/bin/env bash
# =====================================================================
# Aetheria AI - Reintento de `terraform apply` ante "Out of host capacity"
#
# El shape ARM del Always Free (VM.Standard.A1.Flex) esta SIEMPRE muy
# solicitado. Es normalisimo que el primer apply falle con:
#
#     Error: 500-InternalError, Out of host capacity.
#
# No es un error tuyo ni de esta configuracion: Oracle simplemente no
# tiene hueco ARM libre en ese dominio de disponibilidad en ese instante.
# La estrategia que funciona es insistir cada pocos minutos.
#
# Uso:
#   ./retry-apply.sh              # reintenta cada 5 min, hasta 100 veces
#   ./retry-apply.sh 300 200      # intervalo en segundos y numero maximo
#
# Terraform es idempotente: los recursos ya creados (VCN, subred, firewall)
# no se recrean; solo se reintenta lo que falta (la instancia).
# =====================================================================
set -uo pipefail

INTERVAL="${1:-300}"
MAX_TRIES="${2:-100}"
TRY=1

while [ "$TRY" -le "$MAX_TRIES" ]; do
  echo "=================================================="
  echo "Intento $TRY/$MAX_TRIES - $(date '+%Y-%m-%d %H:%M:%S')"
  echo "=================================================="

  if terraform apply -auto-approve; then
    echo ""
    echo "EXITO en el intento $TRY."
    terraform output
    exit 0
  fi

  echo ""
  echo "Fallo el intento $TRY. Si el error es 'Out of host capacity', es lo"
  echo "esperado: reintentando en $INTERVAL s. (Ctrl+C para abandonar.)"
  sleep "$INTERVAL"
  TRY=$((TRY + 1))
done

echo "Agotados $MAX_TRIES intentos. Prueba a cambiar availability_domain_index"
echo "o a bajar instance_ocpus/instance_memory_gb (p.ej. 2 OCPU / 12 GB):"
echo "hay mas hueco libre para peticiones pequenas."
exit 1
