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

## Fase 2 - Backend + Base de datos
- Esquema Supabase versionado
- world-state: resumenes estructurados (no bloques)

## Fase 3 - IA + Validador
- Adaptador LLM intercambiable
- Conversacion en tres niveles
- Planner, Plan JSON, Validador, ejecucion por lista blanca

## Fase 4 - Cloud (IaC)
- Terraform sobre Oracle Cloud Always Free
- SSL, dominio, firewall, backups, monitorizacion