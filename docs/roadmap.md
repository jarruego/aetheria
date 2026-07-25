# Roadmap por fases

Cada fase deja algo funcionando y verificable.

## Fase 0 - Fundacion  (COMPLETA)
- [x] Monorepo + Git + estructura de carpetas
- [x] ADRs iniciales
- [x] Contrato de API (OpenAPI) plugin a backend
- [x] Esqueletos FastAPI (api-gateway, ai-orchestrator) con endpoint de salud
- [x] docker-compose base
- [x] CI (lint + tests) en GitHub Actions
- [x] Repositorio remoto en GitHub
- [x] Verificacion end-to-end en contenedores (health, auth, plan validado)

## Fase 1 - Red Minecraft  (COMPLETA salvo prueba con cliente real)
- [x] Velocity como unico punto de entrada (modern forwarding), escucha en :25565
- [x] Lobby (Paper) + Main (Paper) independientes, solo accesibles via Velocity
- [x] Geyser (UDP 19132) + Floodgate para Bedrock (movil, consola)
- [x] Secreto de forwarding materializado por script (fuera de git)
- [x] Verificacion: contenedores arrancan sin crash-loop y Velocity responde al ping
- [ ] Pendiente (usuario): login real Java + Bedrock y salto lobby<->main

## Fase 2 - Backend + Base de datos  (COMPLETA)
- [x] Postgres local (equivalente a Supabase) en docker-compose
- [x] Migraciones SQL versionadas con runner idempotente (db/migrate.sh, schema_migrations)
- [x] world-state: resumenes estructurados (no bloques) sobre la DB
- [x] API Gateway conectado a la DB via world-state (proxy /v1/worlds, /v1/world/{key}/summary)
- [x] Verificacion: migracion aplicada, seed, resumenes correctos end-to-end, tests

## Fase 3 - IA + Validador  (backend COMPLETO; ejecucion en plugin pendiente)
- [x] Adaptador LLM intercambiable (stub por defecto = coste cero; claude listo con key)
- [x] Conversacion en 3 niveles (L1 codigo, L2 local/stub, L3 LLM), a prueba de gasto
- [x] Planner usa contexto del world-state -> Plan JSON -> Validador (aprobado/rechazado)
- [x] Verificacion end-to-end sin coste (3 niveles + plan con contexto real del mundo)
- [x] Plugin Java (Paper): /aetheria ask|plan|npc, ejecuta plan aprobado por lista blanca
- [x] Acciones fisicas reales: SAY, MOVE_TO (pathfinding), GIVE_ITEM, PLACE_BLUEPRINT
      (catalogo acotado), OPEN_TRADE (mercader); NPC como entidad real (Villager)

## Fase 4 - Cloud (IaC)
- Terraform sobre Oracle Cloud Always Free -> definido en `infra/terraform/` (ADR-0008), pendiente de `apply`
- SSL, dominio, firewall, backups, monitorizacion

## Jugabilidad Minecraft (extras, HECHOS)
- [x] Dos perfiles: lean (cloud/local minimo) y full (lobby+main+creative+End)
- [x] Lobby = hub void (sala con faro y cristaleras), aventura/invulnerable/sin mobs
- [x] Portales del lobby a main (esmeralda) y creativo (diamante); vuelta al lobby en cada mundo
- [x] Comandos /sethome y /home (persistencia local v1)
- [x] Servidor creativo (superflat) como backend de Velocity

## Fase 5 - El mundo recuerda (camino de escritura a la DB)  (PENDIENTE)
Hoy solo se LEE de la DB (world-state). Falta el camino de ESCRITURA para que el mundo
persista lo aprendido/hecho:
- [ ] Endpoint(s) de escritura (gateway/servicio) hacia Postgres/Supabase
- [ ] Persistir jugadores, casas (migrar /home a la DB), `npc_memory`, `plan_audit`, economia
- [ ] El plugin envia eventos del juego (join, encargos, acciones) al backend
- [ ] NPC con memoria real leida del world-state