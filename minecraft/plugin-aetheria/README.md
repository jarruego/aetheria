# Plugin Aetheria (Java / Paper)

El único componente que **ejecuta** cambios en el mundo. Responsabilidades:

- Capturar eventos (interacción con NPC, comandos permitidos) y enviarlos al backend
  como **datos estructurados** (nunca comandos crudos).
- Recibir **planes ya validados** del API Gateway y ejecutar **solo** acciones de la
  lista blanca (`SAY`, `MOVE_TO`, `PLACE_BLUEPRINT`, ...), con logging y rollback.
- Movimiento de NPC, pathfinding, horarios y rutinas: **código**, no IA.

## Lo que el plugin NUNCA hace

- ❌ Llamar directamente a un LLM.
- ❌ Ejecutar una acción que no esté en la lista blanca.
- ❌ Ejecutar un plan que no venga con `status = approved`.

## Stack previsto

- Java 21, API de Paper, Gradle.
- Cliente HTTP hacia `API_GATEWAY` con `Authorization: Bearer <INTERNAL_SERVICE_TOKEN>`.

Se implementa a partir de Fase 1 (esqueleto) y Fase 3 (integración IA completa).
