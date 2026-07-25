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
| `docs/infra/` | **Estado del despliegue cloud y traspaso entre sesiones** |

## Comandos habituales

```bash
# Levantar la topología local (crea .env desde .env.example si falta)
./scripts/dev-up.ps1            # modo LEAN (solo main, sin lobby/End) = igual que cloud
./scripts/dev-up-full.ps1       # modo FULL local (lobby + main + End + más memoria)
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

En GitHub (`https://github.com/jarruego/aetheria`), verificado en local.

**Backend (Fases 0–3): COMPLETO.** F0 Fundación · F1 Red Minecraft · F2
Postgres+migraciones+world-state · F3 IA (adaptador LLM, conversación 3 niveles,
planner→plan→**validador**).

**Plugin Java (Paper): funcional.** `/aetheria ask|plan|npc` ejecuta planes aprobados por
lista blanca (SAY, MOVE_TO, GIVE_ITEM, PLACE_BLUEPRINT, OPEN_TRADE; NPC Villager).
`/sethome`+`/home`. Se compila y despliega solo vía compose (one-shot `plugin-build`,
copia el jar a main/lobby/creative). Detalle: `minecraft/plugin-aetheria/README.md`.

**Red Minecraft — dos perfiles, mismo repo:**
- **lean** (`dev-up.ps1`; y lo que corre en cloud): solo `main`, sin lobby, End off,
  Chunky. Es `docker-compose.yml` a secas.
- **full** (`dev-up-full.ps1`): lobby + main + creative + End, con `docker-compose.full.yml`.
  El **lobby** es un hub *void* (sala con faro y cristaleras) con **portales** a main
  (esmeralda) y creativo (diamante), en aventura/invulnerable/sin mobs; los mundos de
  juego tienen **portal de vuelta** al lobby. `gen-mc-config -Mode lean|full`.

**IA — coste:** por defecto `LLM_PROVIDER=stub` = 0 €. Nivel 3 real: `ollama` (local,
gratis, ADR-0009) o `claude` (de pago). El Nivel 2 nunca gasta (ADR-0007).

**Fase 4 (Cloud/IaC): bloqueada por capacidad de Oracle** (`Out of host capacity`); la red
ya existe (5/7 recursos), falta la instancia ARM → insistir con
`infra/terraform/retry-apply.sh`. Los ajustes cloud (arranque sin lobby, End off, plugin
autodesplegado) YA están aplicados en el repo. Detalle: `docs/infra/fase4-oracle-handoff.md`
(leerlo antes de tocar nada de cloud).

**Pendiente clave — el mundo aún no "recuerda":** no existe todavía un **camino de
escritura a la base de datos** desde el juego. `world-state` es de solo lectura; las casas
(`/home`) se guardan en un fichero local del plugin; las tablas `npc_memory`, `plan_audit`,
`contracts` existen pero nadie escribe en ellas. Construir ese camino (plugin/gateway →
DB) es el siguiente paso grande para memoria de NPC, economía y persistencia real.
→ `docs/roadmap.md`

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
