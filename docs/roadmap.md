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
- [ ] Ejecucion real de acciones (lista blanca) en el plugin Java -> pista Minecraft

## Fase 4 - Cloud (IaC)
- Terraform sobre Oracle Cloud Always Free
- SSL, dominio, firewall, backups, monitorizacion