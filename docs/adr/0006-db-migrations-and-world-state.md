# ADR-0006: Migraciones SQL versionadas y world-state como read-model

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

La persistencia vive en Supabase/Postgres (ADR previo y visión). Necesitamos: (a) que el
esquema evolucione de forma versionada y reproducible en cualquier entorno (local, CI,
Supabase), y (b) que la IA reciba **resúmenes estructurados** del mundo, no bloques.

## Decisión

### 1. Migraciones SQL versionadas con runner propio

- Las migraciones son ficheros `db/supabase/migrations/NNNN_*.sql`, versionados en git,
  inmutables una vez aplicados.
- Un runner minimalista (`db/migrate.sh`, sobre `psql`) aplica las pendientes en orden y
  registra las aplicadas en una tabla `schema_migrations`. Es idempotente.
- No dependemos de un ORM ni de una herramienta pesada: `psql` corre los scripts de forma
  nativa (con `ON_ERROR_STOP` y una transacción por fichero). El mismo runner funciona
  contra Postgres local y contra Supabase (solo cambia `DATABASE_URL`).

### 2. Postgres local en dev = Supabase en prod

En desarrollo levantamos un contenedor `postgres:16` (byte-compatible con Supabase). En
producción, `DATABASE_URL` apunta a la cadena de conexión de Supabase. Las mismas
migraciones aplican sin cambios.

### 3. world-state como read-model

El servicio `world-state` expone **vistas de solo lectura** con resúmenes estructurados
(recuentos de ciudades, parcelas, propietarios, NPC...). Nunca sirve bloques. Es la
fuente que el planner (Fase 3) referencia como contexto (`context_ref`). Quién ESCRIBE la
verdad del mundo (plugin -> gateway -> persistencia) se aborda en fases posteriores.

## Consecuencias

- (+) Esquema reproducible desde cero, en cualquier entorno, sin pasos manuales.
- (+) Cambiar de Postgres local a Supabase = una variable de entorno.
- (+) La IA consume resúmenes baratos, no millones de bloques.
- (-) El runner propio es simple; si en el futuro necesitamos rollback automático o
  migraciones no-lineales, se revisará (posible adopción de Supabase CLI o sqitch).
