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
| `minecraft/` | Velocity, Lobby, Main, plugin Java (imagen `itzg/minecraft-server:java25`; FAWE 2.15.3 exige Java 25) |
| `minecraft/plugin-aetheria/.../TerrainPlanner.java` | Validador+preparador de **terreno compartido** por todos los caminos de construcción (nivelado columna a columna + pilotes sobre agua/hielo) |
| `minecraft/plugin-aetheria/.../BuildRegistry.java` | Registro persistente de cajas 3D (`regions.txt`); ningún camino pisa lo ya construido |
| `minecraft/plugin-aetheria/.../SettlementModule.java` | Pueblo vivo multi-aldea (colonos, oficios, gobierno, edificios permanentes) |
| `minecraft/plugin-aetheria/.../SchematicModule.java` `CatalogModule.java` | Esquemáticos FAWE + galería del creativo |
| `infra/` | Terraform (Oracle Cloud) + Docker (Fase 4) |
| `docs/infra/` | **Estado del despliegue cloud y traspaso entre sesiones** |
| `docs/ia-local.md` | **IA real a coste cero** (Ollama): instalación, modelos, problemas |
| `docs/seguridad.md` | **Qué protege el sistema y qué no** (comprobado atacándolo) |
| `docs/guia-jugador.md` | **Guía del jugador**: todo lo que se puede hacer en el server |

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

**Plugin Java (Paper): funcional.** `/aetheria ask|plan|npc|servicio|cronica|schem` ejecuta
planes aprobados por lista blanca (SAY, MOVE_TO, GIVE_ITEM, PLACE_BLUEPRINT, OPEN_TRADE; NPC
Villager). `/sethome`+`/home` (en DB), `/balance`+`/pay` (economía), `/deshacer` (revertir
construcciones con reembolso). Se compila y despliega
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
código** (`NpcRoutineModule`, no el LLM). Todos son **colonos procedurales** conversables
(ver "servidor vivo") con persona propia (nombre, edad, oficio, familia); resucitan si algo
los borra. Activable con `npc-routines.enabled`.

**Fase 8 (el mundo evoluciona solo): COMPLETA en su núcleo.** Simulación económica por
**ticks en el backend** (`world-state/simulation.py`) que corre aunque no haya nadie
conectado: los negocios del pueblo producen ingresos y pagan gastos (persistido en
cuentas/transacciones) y cada suceso se registra en la **crónica** (`world_events`,
migración 0005). En el juego: `/aetheria cronica` (un **libro** maquetado, máx. 50 páginas,
lo más reciente primero, con icono por tipo y fecha en los sucesos notables; la economía se
resume a cambios de prosperidad). Tick manual `POST /internal/sim/tick` (o cron externo) +
bucle cada `SIM_TICK_SECONDS`. Simulación por código, nunca el LLM. Población objetivo
acotada (`sim_min_population=2`, `sim_max_population=20`; economía reequilibrada: upkeep 0.6,
crecimiento lento).

**Fase 9 (estructuras sociales): COMPLETA en su núcleo.** **Parcelas reclamables por chunk**
con propietario, persistidas en `plots`. `/claim` (cuesta AET, integra la economía),
`/claim info`, `/unclaim`. **Protección**: dentro de la parcela de otro nadie rompe/pone
bloques (`ClaimModule` con caché en memoria chunk→dueño, cero red por bloque). El backend
valida solape (409), fondos (400, sin cobrar) y propiedad. Roadmap F0–F9 al día.

**Capa de "servidor vivo" (encima de F0–F9).** Para que al entrar se note vida:
- **Aldea física** (`VillageModule`): el plugin construye a **cota fija** (no trepa entre
  reinicios) la plaza con pozo y campana al **sur del spawn** (el portal queda al norte); el
  resto (casas, edificios de oficio, mejoras cívicas) lo hace crecer solo `SettlementModule`.
- **Terreno y anti-solape compartidos** (`TerrainPlanner` + `BuildRegistry`): *todos* los
  caminos de construcción (aldea autónoma, arquitecto, decorador, blueprint por chat y
  esquemáticos) pasan por el mismo validador/preparador de terreno (nivelado **columna a
  columna** con material coherente + **pilotes** sobre agua/hielo, sin relleno sólido ni
  rechazo) y por el mismo registro persistente de cajas 3D (`regions.txt`), así que **nadie
  pisa lo ya construido**. El arquitecto prueba 2-3 huecos al lado antes de rechazar. No se
  construye sobre agua/hielo en la aldea (reubica); el spawn del main se reubica si cae en
  hielo/desierto/mucha agua. → ADR-0014
- **Pueblo vivo con varias aldeas** (`SettlementModule`): NO hay NPC fijos (Nara/Pol/Sella
  ya no existen); **toda** la población son **colonos generados por procedimiento**. Un mundo
  nuevo arranca con **dos fundadores de distinto sexo**. Cada colono tiene **género** (m/f;
  ~100 nombres por sexo), **edad** (envejece ~2 años/día real; se jubila a 65, muere ~80-90 y
  su casa se **demuele y renaturaliza** —hierba, flores, brotes—), **oficio** y **familia**.
  Los solteros viven en **casa pequeña** (1 cama); al **casarse** se les construye una
  **mediana** (3 camas) y se derriban sus dos casitas. Nacen **hijos** solo de pareja casada
  de distinto sexo (`bearChild`). Los recién llegados toman **el oficio que falta** en su
  aldea (8 profesiones, cada una con un **puesto de trabajo temático**: huerto, embarcadero,
  aprisco, taller de cantero, biblioteca, herrería, carnicería, taller de arquero). Al morir
  alguien, un sucesor (preferentemente un hijo) **cambia de oficio** para cubrir la vacante
  (evento *relevo*). Los edificios de oficio son **permanentes** (no se derriban al morir su
  aldeano; los hereda otro o esperan) y están persistidos (`buildings.txt`). **Memoria por
  individuo**: cada colono tiene su propio id de memoria (`colono:Nombre`), no comparten lo
  que les cuentas (`ConversationManager`).
- **Multi-aldea autofundada** (`foundNewTown`/`assignTown`, `PER_TOWN=8`): cuando una aldea
  llega a 8 vecinos, una pareja parte a **fundar una aldea nueva** con nombre propio a 220-400
  bloques (~24 nombres curados y luego "Aldea N"); se registra en la crónica (evento
  *fundacion*). Al **entrar** en el radio (~48 bloques) de una aldea aparece **su nombre en
  pantalla** (`onMove`, título de bienvenida). Cada aldea tiene **alcalde** (el vecino más
  veterano) con su cartel en la plaza (evento *gobierno* al cambiar) y un **granero** (barril)
  donde cada oficio deposita su producción física (trigo, lana, hierro...).
- **Protección de la aldea**: las casas de colono y el núcleo del pueblo no se rompen ni ponen
  (aparte de las parcelas F9) y **resisten explosiones** de creeper/TNT (`onExplode`). Ojo: el
  **terreno natural** (tierra/piedra/arena/mineral) junto a una casa **SÍ es recolectable**
  (`terrain()`); solo se protege lo construido (madera/ladrillo/cristal).
- **Trabajos** (`JobsModule`): se gana AET por minar/talar/cosechar (maduros)/cazar;
  recompensa por lotes cada 20 s con action bar. **Mercado** (`ShopModule`): `/sell [all]`,
  `/worth`, `/shop`. Backend: `/internal/reward` → `/v1/reward` (paga desde la cuenta banco).
- **HUD** (`HudModule`): marcador lateral (saldo + prosperidad + **Habitantes** (nº de
  aldeanos vivos) + **Jugadores**), bienvenida, **libro-guía** en la 1ª conexión y `/guia`.
- **Esquemáticos FAWE** (`SchematicModule` + `SchematicWriter`): `/aetheria schem
  list|paste|save` (jugadores) y desde consola `savecube|savecatalog|pastestreet` (nutre y
  pega el catálogo como "calle de muestra"). Solo activo si FAWE/WorldEdit está instalado (se
  detecta en `AetheriaPlugin.onEnable`); el catálogo es la carpeta de esquemáticos de FAWE.
  La imagen del server está fijada a **`itzg/minecraft-server:java25`** porque FAWE 2.15.3 lo
  exige (en Java 21 no arranca). El pegado pasa por `TerrainPlanner` (nivela + pilotes).
- **Conserje del lobby** (`LobbyGuideModule`): **un solo** NPC (Aeon) que ronda el lobby,
  con nombre sobre la cabeza; su persona en el orchestrator conoce **todo** el server y da
  los comandos exactos. Ya no hay un guía por portal.
- **Sociedad que prospera/decae**: la simulación F8 tiene festivales, penurias y pérdidas;
  se calcula la **prosperidad** (`/internal/world/prosperity` → `/v1/prosperity`), visible
  en el HUD y en `/aetheria cronica`.
- Guía para el dueño del avance: `docs/guia-jugador.md`.

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
