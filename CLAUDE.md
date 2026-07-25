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
| `services/api-gateway/` | API REST pública, auth, reenvío al backend |
| `services/world-state/` | Resúmenes del mundo para la IA (Fase 2) |
| `db/supabase/migrations/` | Esquema versionado |
| `minecraft/` | Velocity, Lobby, Main, plugin Java (Fase 1) |
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

# Tests / lint por servicio (ver también CONTRIBUTING.md)
cd services/<servicio>
pip install -e ".[dev]" && pytest -q && ruff check src tests
```

## Estado actual

**Fase 0 (Fundación): COMPLETA** y verificada end-to-end en contenedores, subida a
GitHub (`https://github.com/jarruego/aetheria`). **Siguiente: Fase 1** (red Minecraft:
Velocity + Lobby + Main + Geyser/Floodgate). → `docs/roadmap.md`

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
