#!/usr/bin/env bash
# Runner de migraciones minimalista sobre psql (ADR-0006).
# Aplica db/supabase/migrations/*.sql en orden, registrando las aplicadas en
# schema_migrations. Idempotente: re-ejecutarlo no repite migraciones ya aplicadas.
# Requiere DATABASE_URL y los .sql montados en /migrations.
set -euo pipefail

: "${DATABASE_URL:?DATABASE_URL no definido}"
MIGRATIONS_DIR="${MIGRATIONS_DIR:-/migrations}"

echo "[migrate] Esperando/usando DB en $DATABASE_URL"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c \
  "create table if not exists schema_migrations (version text primary key, applied_at timestamptz not null default now());"

shopt -s nullglob
for f in "$MIGRATIONS_DIR"/*.sql; do
  v="$(basename "$f" .sql)"
  applied="$(psql "$DATABASE_URL" -tA -c "select 1 from schema_migrations where version='$v';")"
  if [ "$applied" = "1" ]; then
    echo "[migrate] skip  $v (ya aplicada)"
    continue
  fi
  echo "[migrate] apply $v"
  # -1 = una transaccion por fichero; si algo falla, no se marca como aplicada.
  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 -f "$f"
  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "insert into schema_migrations(version) values ('$v');"
done

echo "[migrate] Migraciones al dia."
