# ADR-0010: Camino de escritura a la base de datos (world-state read+write)

- **Estado:** Aceptada
- **Fecha:** 2026-07-26

## Contexto

Hasta ahora la DB solo se leía: `world-state` era un *read-model* (ADR-0006) y nada
persistía lo que ocurre en el juego. Para que "el mundo recuerde" (jugadores, casas,
memoria de NPC, auditoría de planes, economía) hace falta un **camino de escritura**
desde el plugin hasta Postgres/Supabase (Fase 5).

## Decisión

El servicio **`world-state` pasa a ser el dueño de la persistencia: lecturas Y
escrituras.** Ya tiene el pool `asyncpg`, la `DATABASE_URL` y los modelos del dominio, así
que concentrar el acceso a la DB en un único servicio es lo más simple para el tamaño
actual del proyecto.

Flujo: **Plugin → API Gateway (auth) → world-state → Postgres**. El plugin nunca habla con
la DB directamente; el gateway sigue siendo el único punto de entrada del plugin.

Los endpoints de escritura viven bajo el mismo servicio pero **con rutas propias**
(`/internal/players/upsert`, `/internal/homes`), separadas de las de lectura. Esto deja la
puerta abierta a **separar lectura y escritura en dos servicios más adelante** (CQRS) sin
tocar al plugin: bastaría reenrutar en el gateway.

Primer uso (migración `0002`): registrar jugadores al entrar y **migrar `/home` a la DB**
(una casa por jugador y servidor; los mundos de distintos servidores comparten nombre).

## Consecuencias

- (+) El mundo empieza a persistir estado real; base para memoria de NPC, economía, etc.
- (+) Un solo sitio con acceso a la DB (menos superficie, más simple).
- (+) Contrato versionado: los endpoints están en `contracts/openapi.yaml`.
- (-) `world-state` deja de ser un read-model puro. Se asume a cambio de simplicidad; si
  la carga de lectura y escritura diverge, se separan (las rutas ya están namespaciadas).
- (-) Las escrituras deben ser idempotentes/`upsert` (el plugin puede reintentar).
