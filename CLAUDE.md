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
| `docs/ia-local.md` | **IA real a coste cero** (Ollama): instalación, modelos, problemas |
| `docs/seguridad.md` | **Qué protege el sistema y qué no** (comprobado atacándolo) |

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

**Plugin Java (Paper): funcional.** `/aetheria ask|plan|npc|servicio` ejecuta planes
aprobados por lista blanca (SAY, MOVE_TO, GIVE_ITEM, PLACE_BLUEPRINT, OPEN_TRADE; NPC
Villager). `/sethome`+`/home` (en DB), `/balance`+`/pay` (economía). Se compila y despliega
solo vía compose (one-shot `plugin-build`, copia el jar a main/lobby/creative). Detalle:
`minecraft/plugin-aetheria/README.md`.

**Fase 5 (el mundo recuerda): COMPLETA.** Camino de escritura plugin→gateway→world-state→
Postgres (ADR-0010). Jugadores y casas persisten en DB; los NPC recuerdan (memoria en dos
capas: ~10 turnos verbatim + **ficha evolutiva** del jugador que condensa lo viejo y poda
lo ya resumido, migración 0004); persona humana por NPC + contexto de mundo/elenco;
auditoría de planes.

**Fase 6 (economía y servicios IA): COMPLETA en su núcleo (ADR-0011).** Moneda **AET**
sobre `accounts`/`transactions` (sin migración nueva). Cuentas perezosas con 100 AET
inicial; cuenta "banco" del sistema como sumidero. `/balance` y `/pay` (transferencias
atómicas con control de fondos). **Servicios inteligentes de PAGO** (`/aetheria servicio
<arquitecto|decorador|urbanista> <qué>`): la IA propone → validador aprueba → **solo
entonces se cobra** (nunca se paga por un plan rechazado ni sin fondos). Se venden
servicios, nunca ventajas.

**Red Minecraft — dos perfiles, mismo repo:**
- **lean** (`dev-up.ps1`; y lo que corre en cloud): solo `main`, sin lobby, End off,
  Chunky. Es `docker-compose.yml` a secas.
- **full** (`dev-up-full.ps1`): lobby + main + creative + End, con `docker-compose.full.yml`.
  El **lobby** es un hub *void* (sala con faro y cristaleras) con **portales** a main
  (esmeralda) y creativo (diamante), en aventura/invulnerable/sin mobs; los mundos de
  juego tienen **portal de vuelta** al lobby. `gen-mc-config -Mode lean|full`.

**IA — coste:** por defecto `LLM_PROVIDER=stub` = 0 €. Nivel 3 real: `ollama` (local,
gratis, ADR-0009) o `claude` (de pago). El Nivel 2 nunca gasta (ADR-0007).

**IA local (ADR-0009): IMPLEMENTADA y verificada en real.** Proveedor `ollama` que habla
el protocolo de OpenAI (`/v1/chat/completions`), así que vale también para LM Studio,
vLLM o llama.cpp — cambiar de motor es cambiar `OLLAMA_BASE_URL`. Tres cosas que hay que
saber antes de tocarlo:
1. **Un modelo por nivel, de familia distinta a propósito** (medido, no estimado):
   `gemma3:4b` (instruct) para el Nivel 2 y `qwen3:8b` (razonamiento) para el Nivel 3.
   **No pongas un modelo de razonamiento en el Nivel 2**: divaga en la charla casual.
2. **Se envía `reasoning_effort: "none"` siempre.** Sin eso, un modelo de razonamiento
   gasta *todo* el presupuesto de tokens pensando y devuelve vacío. `enable_thinking`
   NO funciona en Ollama; `reasoning_effort` sí.
3. **Desde Docker la URL es `host.docker.internal`**, nunca `localhost`.
→ Guía: `docs/ia-local.md`. No sirve en cloud (la instancia ARM no tiene GPU).

**Blindaje de cartera reforzado:** `get_local_provider()` ahora **rechaza por código**
`claude`/`openai` en el Nivel 2 (antes era solo una regla escrita). El Nivel 2 se dispara
en cada frase de cada NPC: una variable mal puesta vaciaría la cuenta sin avisar.

**Seguridad (auditado atacándolo, 2026-07-26 — `docs/seguridad.md`).** La lista blanca
aguanta: pedir `/op` o borrar el mundo devuelve pan. Tres agujeros encontrados y cerrados:
1. **Puertos internos publicados en `0.0.0.0`** — `/internal/*` respondía sin token desde
   cualquier equipo de la LAN, y **Postgres entero** estaba expuesto. Ahora todo va a
   `127.0.0.1:` en compose. **Regla: en `ports:` solo Velocity va sin prefijo de interfaz.**
2. **El planner reemitía el texto del jugador** al chat (inyección de salida: un NPC como
   altavoz) **y el resumen del world-state** (divulgación). Ya no: frase fija.
3. **Sin saneo del chat** — nuevo `validator/text_safety.py` filtra códigos de formato
   (`§c`, `&l`), saltos de línea y controles, y corta a 200 chars. Está **en el validador,
   no en el planner**, para que proteja también los planes generados por LLM. Orden:
   sanear → validar.

**Trampa Java↔FastAPI (costó un rato):** `HttpClient.newBuilder()` usa **HTTP/2 por
defecto** y manda `Upgrade: h2c`; uvicorn no lo soporta y **descarta el cuerpo** → 422 con
`body: null` y los NPC decían *"(no puedo responder ahora)"*. Solución: forzar
`.version(HttpClient.Version.HTTP_1_1)` en `GatewayClient`. Si alguna llamada al gateway
da 422 con `loc: ["body"]`, es esto.

**Fase 4 (Cloud/IaC): bloqueada por capacidad de Oracle** (`Out of host capacity`); la red
ya existe (5/7 recursos), falta la instancia ARM → insistir con
`infra/terraform/retry-apply.sh`. Los ajustes cloud (arranque sin lobby, End off, plugin
autodesplegado) YA están aplicados en el repo. Detalle: `docs/infra/fase4-oracle-handoff.md`
(leerlo antes de tocar nada de cloud).

**Fase 7 (NPC vivos): COMPLETA en su núcleo.** Vecinos con **rutina diaria** por horario
(trabajan de día, plaza al atardecer, casa de noche) que se mueven con **pathfinding por
código** (`NpcRoutineModule`, no el LLM). Son conversables (Nara/granjera, Pol/vigilante) y
resucitan si algo los borra. Activable con `npc-routines.enabled`.

**Fase 8 (el mundo evoluciona solo): COMPLETA en su núcleo.** Simulación económica por
**ticks en el backend** (`world-state/simulation.py`) que corre aunque no haya nadie
conectado: los negocios del pueblo producen ingresos y pagan gastos (persistido en
cuentas/transacciones) y cada suceso se registra en la **crónica** (`world_events`,
migración 0005). En el juego: `/aetheria cronica`. Tick manual `POST /internal/sim/tick`
(o cron externo) + bucle cada `SIM_TICK_SECONDS`. Simulación por código, nunca el LLM.

**Fase 9 (estructuras sociales): COMPLETA en su núcleo.** **Parcelas reclamables por chunk**
con propietario, persistidas en `plots`. `/claim` (cuesta AET, integra la economía),
`/claim info`, `/unclaim`. **Protección**: dentro de la parcela de otro nadie rompe/pone
bloques (`ClaimModule` con caché en memoria chunk→dueño, cero red por bloque). El backend
valida solape (409), fondos (400, sin cobrar) y propiedad. Roadmap F0–F9 al día.

**Todas las fases del plan (F0–F9) están en su núcleo COMPLETAS.** Lo que queda son
mejoras transversales (skins humanas para NPC, backups/monitorización, F4 cloud pendiente
de capacidad Oracle) y profundizar cada sistema (ciudades/gobiernos, agendas de NPC más
ricas, que la simulación haga crecer estructuras). → `docs/roadmap.md`

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
