# Roadmap por fases

Cada fase deja algo funcionando y verificable.

## Fase 0 - Fundacion  (EN CURSO)
- [x] Monorepo + Git + estructura de carpetas
- [x] ADRs iniciales
- [x] Contrato de API (OpenAPI) plugin a backend
- [x] Esqueletos FastAPI (api-gateway, ai-orchestrator) con endpoint de salud
- [x] docker-compose base
- [ ] CI (lint + tests) en GitHub Actions
- [ ] Repositorio remoto en GitHub

## Fase 1 - Red Minecraft
- Velocity como unico punto de entrada (modern forwarding)
- Lobby (Paper) + Main (Paper) independientes
- Geyser + Floodgate (Bedrock, movil, consola)

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