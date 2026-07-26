# ADR-0012: Simulación autónoma del mundo (Fase 8)

- **Estado:** Aceptada
- **Fecha:** 2026-07-26

## Contexto

La visión pide un mundo que "evolucione durante años" y siga su curso aunque no haya
nadie conectado. Hasta ahora todo cambio venía de una acción de un jugador o de un NPC
disparado por un jugador. Faltaba un motor que hiciera *latir* la economía por su cuenta.

## Decisión

Una **simulación por ticks** vive en el servicio **`world-state`** (el dueño de la DB,
ADR-0010), como una `asyncio.Task` de fondo arrancada en el `lifespan` de FastAPI. Cada
tick evoluciona la economía: unos **negocios del pueblo** (cuentas `owner_type='company'`)
producen ingresos y pagan gastos contra la cuenta "banco" del sistema, todo persistido en
`accounts`/`transactions`, y cada suceso se resume en una **crónica** (`world_events`,
migración 0005) legible por el jugador (`/aetheria cronica`).

Puntos clave del diseño:

- **Simulación por código, nunca el LLM.** Igual que las rutinas de NPC (Fase 7), la
  evolución del mundo es determinista/estocástica en el backend. El LLM sigue solo
  proponiendo planes que pasan por el validador. El dinero solo lo mueven transacciones SQL.
- **Dos formas de disparar el tick:** el bucle de fondo (`SIM_TICK_SECONDS`) y un endpoint
  manual `POST /internal/sim/tick`. Lo segundo hace la simulación **testeable de forma
  determinista** y permite, si algún día conviene, dispararla desde un **cron externo**
  (un scheduler o una Edge Function de Supabase) en vez del bucle en proceso.
- **Degrada sin caer:** si la DB no está lista o un tick falla, se registra y se continúa;
  la simulación nunca tumba el servicio. Se puede apagar con `SIM_ENABLED=false`.

## Consecuencias

- (+) El mundo tiene vida propia y una crónica: al volver, el jugador ve qué pasó.
- (+) Reutiliza la economía (ADR-0011) sin esquema nuevo salvo la tabla de crónica.
- (+) El tick es una función pura sobre una conexión: fácil de mover a un worker o cron
  dedicado cuando haya varias instancias (hoy un único proceso evita ticks duplicados).
- (-) Con varias réplicas de `world-state` habría que elegir un líder o mover el tick a un
  cron externo para no simular por duplicado. Se asume mientras haya una sola instancia.
- (-) De momento la simulación solo mueve dinero; hacer crecer ciudades/estructuras es
  trabajo futuro (se apoyará en las tablas `cities`/`plots` que ya existen).
