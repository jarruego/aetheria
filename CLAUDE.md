# CLAUDE.md

Guía de entrada para cualquier sesión de Claude Code en este repo.
**Este archivo se mantiene BREVE a propósito**: es un índice + las reglas imprescindibles.
El detalle vive en `docs/` y se lee solo cuando hace falta (menos tokens por sesión).

## Qué es Aetheria (en una frase)

Un servidor de Minecraft persistente donde la IA es el "sistema operativo del mundo"
(los NPC son su cara física), gobernado por servicios backend desacoplados.
→ Visión completa: `README.md` y `docs/architecture/overview.md`.

## Reglas de oro (no negociables)

1. **La IA nunca ejecuta acciones.** Solo propone un *plan JSON*; un **validador
   determinista** lo aprueba/rechaza; el plugin ejecuta solo acciones de una **lista
   blanca**. → `docs/architecture/security-flow.md`
2. **Todo entra por Velocity.** Paper nunca es accesible directamente. → ADR-0005
3. **La IA está desacoplada.** Cambiar Claude↔OpenAI↔local = una variable de entorno,
   sin tocar Minecraft. → ADR-0004
4. **Persistencia en Supabase/Postgres**, nunca en YAML. → `db/supabase/migrations/`
5. **Microservicios desde el día 1.** Nunca asumir una sola máquina. → ADR-0003
6. **IaC + reproducibilidad.** Nada se instala a mano; Docker/Terraform/scripts.

## Mapa del repo

| Ruta | Qué es |
|---|---|
| `docs/architecture/` | Visión general + flujo de seguridad |
| `docs/adr/` | Decisiones de arquitectura (leer antes de cambios estructurales) |
| `docs/roadmap.md` | Plan por fases y estado actual |
| `contracts/openapi.yaml` | Contrato REST Plugin↔Backend (fuente de verdad de la API) |
| `services/ai-orchestrator/` | Planner + **validador** + adaptador LLM + conversación 3 niveles |
| `services/api-gateway/` | API REST pública, auth, reenvío al backend y al world-state |
| `services/world-state/` | Resúmenes estructurados del mundo (read-model sobre Postgres) |
| `db/supabase/migrations/` | Esquema versionado + `db/migrate.sh` (runner idempotente) |
| `minecraft/` | Velocity, Lobby, Main, plugin Java |
| `infra/` | Terraform (Oracle Cloud) + Docker (Fase 4) |

## Comandos habituales

```bash
# Levantar la topología local (crea .env desde .env.example si falta)
./scripts/dev-up.ps1            # Windows
docker compose up -d --build    # equivalente directo
docker compose down             # parar

# Salud
#   API Gateway     -> http://localhost:8080/health
#   AI Orchestrator -> http://localhost:8090/health
#   World-State     -> http://localhost:8070/health
#   Minecraft Java  -> localhost:25565   | Bedrock -> localhost:19132 (UDP)

# Base de datos: las migraciones se aplican solas (servicio one-shot 'migrate').
# Datos de demo para dev:  psql "$DATABASE_URL" -f db/seed-dev.sql

# Tests / lint por servicio (ver también CONTRIBUTING.md)
cd services/<servicio>
pip install -e ".[dev]" && pytest -q && ruff check src tests
```

## Estado actual

**Fases 0–3: COMPLETAS** (F3 backend; ejecución en plugin pendiente), en GitHub
(`https://github.com/jarruego/aetheria`).
- **F0 Fundación**: monorepo, backend (gateway + orchestrator + validador), CI.
- **F1 Red Minecraft**: Velocity + Lobby + Main + Geyser/Floodgate en docker-compose.
- **F2 Backend + DB**: Postgres + migraciones versionadas + world-state (read-model).
- **F3 IA + Validador**: adaptador LLM (default `stub` = **coste cero**), conversación 3
  niveles, planner que usa el contexto del world-state → plan → validador.
- **Plugin Java (Paper)**: `/aetheria ask|plan`; ejecuta planes aprobados por **lista
  blanca** (SAY operativo). Cierra el bucle IA → mundo. Cargado y verificado en `main`.

**Coste:** por defecto NO se gasta nada (`LLM_PROVIDER=stub`). Para IA real: `LLM_PROVIDER=claude`
+ `ANTHROPIC_API_KEY`. El Nivel 2 siempre usa proveedor local (nunca gasta). → ADR-0007.

**Siguiente: Fase 4** (Cloud/IaC en `infra/terraform/`) y acciones "físicas" ricas del
plugin (mover NPC, blueprints). → `docs/roadmap.md`

## Convenciones

- Python 3.12 + FastAPI, tipado, `ruff` (línea 100).
- **Commits pequeños y atómicos**; decisiones estructurales = un ADR nuevo (ADR-0001).
- Cambios en la API → primero `contracts/openapi.yaml`, luego el código.
- La seguridad no se toca: ningún camino de código entrega un plan sin validar.

## Notas del entorno local (máquina Windows del dueño)

- El antivirus (Defender Controlled Folder Access) puede bloquear escrituras; la carpeta
  del proyecto está en la lista de exclusiones. Si fallan escrituras, revisar eso.
- Crear venvs **dentro** del proyecto puede fallar por el AV: usar `%TEMP%` para venvs
  locales, o simplemente **verificar con Docker** (compila en Linux, sin AV de Windows).
- Docker Desktop debe estar **arrancado** antes de `docker compose`.
