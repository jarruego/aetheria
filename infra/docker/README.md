# Docker (infraestructura de contenedores)

El `docker-compose.yml` de la **raíz** del repo levanta la topología local completa
(hoy: `api-gateway` + `ai-orchestrator`; en Fase 1 se añaden Velocity, Lobby y Main).

Esta carpeta se reserva para artefactos de infraestructura de contenedores que no son el
compose principal, por ejemplo:

- Overrides por entorno (`docker-compose.prod.yml`, `docker-compose.staging.yml`).
- Configuración de red/volúmenes compartida.
- Ficheros de arranque de servicios auxiliares (monitorización, logs) en fases futuras.

Cuando el proyecto crezca, la migración a Kubernetes se documentará aquí con su ADR.

## Despliegue en cloud (Fase 4)

La máquina de Oracle Cloud usa **el mismo `docker-compose.yml` de la raíz**, sin overrides:
`cloud-init` clona el repo, genera el `.env` y ejecuta `docker compose up -d --build`.
Ver `infra/terraform/README.md` y ADR-0008.

Dos limitaciones conocidas del compose actual cuando corre en la VM Always Free
(2 OCPU / 12 GB), pendientes de resolver **en el compose de la raíz**, no aquí:

- `PAPER_MEMORY` es una sola variable para `lobby` y `main`, que necesitan memorias muy
  distintas (hub mínimo vs. mundo real). `cloud-init` ya deja `LOBBY_MEMORY` y
  `MAIN_MEMORY` escritas en el `.env` de la máquina, listas para usarse.
- El `lobby` necesita ajustes de mundo mínimo (`VIEW_DISTANCE`, `SIMULATION_DISTANCE`,
  `SPAWN_MONSTERS`, `SPAWN_ANIMALS`) para caber en su presupuesto de memoria y CPU.

El desglose exacto está en `infra/terraform/README.md`, sección *Dimensionamiento*.
