# Docker (infraestructura de contenedores)

El `docker-compose.yml` de la **raíz** del repo levanta la topología local completa
(hoy: `api-gateway` + `ai-orchestrator`; en Fase 1 se añaden Velocity, Lobby y Main).

Esta carpeta se reserva para artefactos de infraestructura de contenedores que no son el
compose principal, por ejemplo:

- Overrides por entorno (`docker-compose.prod.yml`, `docker-compose.staging.yml`).
- Configuración de red/volúmenes compartida.
- Ficheros de arranque de servicios auxiliares (monitorización, logs) en fases futuras.

Cuando el proyecto crezca, la migración a Kubernetes se documentará aquí con su ADR.
