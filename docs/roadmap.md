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
- [x] Portal de vuelta del main: zona segura 10x10 (sin spawns de mobs, barrido periodico
      de hostiles y sin dano de monstruos al jugador), INDESTRUCTIBLE (no se rompe/pone/
      inunda/explota), decorada (tema geoda: amatista + calcita/basalto + faroles de lampara
      marina) y el jugador aparece 3 casillas al norte para no reentrar sin querer
- [x] NPC con rutina: se detienen y te miran al hablarles; movimiento fiable (reemision de
      camino 2x/seg + rescate anti-atasco) por casa(~7)/trabajo(~13)/plaza(~3) desde el spawn
- [x] Aldea FISICA (VillageModule): plaza con pozo y campana a cota fija; el resto del pueblo
      lo hace crecer solo SettlementModule
- [x] Construccion compartida: TerrainPlanner (nivelado columna a columna + pilotes sobre
      agua/hielo) y BuildRegistry (registro persistente de cajas 3D, regions.txt) usados por
      TODOS los caminos (aldea, arquitecto, decorador, blueprint, esquematicos): nadie pisa lo
      ya construido; el arquitecto prueba huecos al lado antes de rechazar
- [x] Edificios de oficio PERMANENTES (Blueprint.workplaceShowcase): herreria, granja, cantera,
      corral, biblioteca, pescaderia, carniceria, taller de arquero; persistidos (buildings.txt)
      y protegidos; no se derriban al morir el aldeano (los hereda otro)
- [x] PUEBLO VIVO PROCEDURAL (SettlementModule): sin NPC fijos; toda la poblacion son colonos
      generados (genero m/f, ~100 nombres por sexo, edad que envejece/jubila/muere, oficio y
      familia). Un mundo nuevo arranca con DOS fundadores de distinto sexo. Casa pequena de
      soltero -> mediana al casarse; nacen hijos de pareja casada; puestos de trabajo tematicos
      por oficio; al morir alguien un sucesor cambia de oficio (relevo)
- [x] VARIAS ALDEAS autofundadas: al llegar a 8 vecinos, una pareja funda una aldea nueva con
      nombre propio a 220-400 bloques (crónica: fundacion). Titulo de bienvenida con el nombre
      del pueblo al entrar en su radio. Alcalde por aldea (cartel en la plaza) + granero donde
      cada oficio deposita produccion fisica
- [x] Proteccion de la aldea: casas de colono y nucleo a prueba de creeper/TNT; el terreno
      natural (tierra/piedra/arena/mineral) junto a las casas SI es recolectable. Al morir o
      emigrar un colono su casa se demuele y el solar se renaturaliza
- [x] Esquematicos FAWE (SchematicModule): /aetheria schem list|paste|save y consola
      savecube|savecatalog|pastestreet (solo si FAWE/WorldEdit esta instalado)
- [x] Trabajos (ganar AET por minar/talar/cosechar/cazar) + Mercado (/sell,/worth,/shop)
- [x] HUD lateral (saldo + prosperidad + Habitantes + Jugadores), bienvenida, libro-guia y /guia
- [x] Conserje unico del lobby (Aeon) que ronda, con nombre y que conoce todo el server
- [x] Sociedad que prospera/decae: festivales, penurias y prosperidad (en HUD y cronica)
- [x] Guia del jugador (docs/guia-jugador.md)

## Fase 5 - El mundo recuerda (camino de escritura a la DB)  (COMPLETA)
- [x] Camino de escritura: plugin -> gateway -> world-state -> Postgres (ADR-0010)
- [x] Registrar jugadores al entrar (tabla players deja de ser solo la semilla)
- [x] Casas (/home) migradas a la DB (migracion 0002, una por servidor)
- [x] Memoria de conversacion de NPC (migracion 0003): los NPC recuerdan lo que les cuentas
- [x] Personalidad humana por NPC (nombre + caracter; ya no hablan como robots)
- [x] Auditoria de planes (plan_audit): cada plan de la IA queda registrado
- (La economia/encargos se traslada a la Fase 6: es un sistema grande por si mismo)

## Fase 6 - Economia y servicios IA  (COMPLETA en su nucleo)
- [x] Moneda AET, cuentas y transacciones sobre accounts/transactions (ADR-0011, sin
      migracion nueva). Cuentas perezosas con saldo inicial 100 AET; cuenta "banco" del
      sistema como sumidero.
- [x] Saldo y pagos entre jugadores: /balance y /pay (comandos in-game + /v1/balance,
      /v1/pay). Transferencias atomicas con control de fondos insuficientes.
- [x] Servicios inteligentes de PAGO: Arquitecto (50), Decorador (20), Urbanista (80).
      /aetheria servicio <tipo> <que quieres>. Orden seguro: la IA propone -> validador
      aprueba -> SOLO entonces se cobra (nunca se paga por un plan rechazado ni sin fondos).
      El modelo de negocio de la vision: vender servicios, nunca ventajas.
- [ ] Profesiones y empresas (futuro)
- [ ] Encargos/contratos (contracts) entre jugadores y NPC (futuro)

## Fase 7 - NPC vivos (rutinas)  (COMPLETA en su nucleo)
- [x] Vecinos con RUTINA DIARIA por horario: trabajan de dia, se reunen en la plaza al
      atardecer y se van a casa de noche (NpcRoutineModule, mundo principal).
- [x] Movimiento por CODIGO (pathfinding Paper: Mob.getPathfinder().moveTo), no por el LLM.
      La IA sigue solo proponiendo planes; las rutinas son deterministas.
- [x] Son conversables (colonos procedurales con persona propia: nombre, edad, oficio y
      familia) y resucitan si algo los elimina. Activable con npc-routines.enabled.
- [x] Agendas mas ricas: los vecinos PASEAN/exploran el pueblo a ratos (no clavados en el
      puesto) y hay SOCIEDAD (matrimonios que conviven, viudedad, familias). Ver "servidor vivo".
- [x] Oficios que PRODUCEN recursos fisicos: cada oficio deposita su produccion (trigo, lana,
      hierro, pescado...) en el granero (barril) de su aldea.

## Fase 8 - El mundo evoluciona solo  (COMPLETA en su nucleo)
- [x] Simulacion economica por TICKS en el backend (world-state), corre aunque no haya
      nadie conectado: los negocios del pueblo producen ingresos y pagan gastos, y el
      saldo se persiste en cuentas/transacciones.
- [x] Cronica del mundo (migracion 0005, world_events): cada suceso autonomo queda
      registrado. Se consulta en el juego con /aetheria cronica ("que paso mientras no
      estabas") y via /v1/world-events.
- [x] Tick manual (POST /internal/sim/tick) para pruebas o un cron externo; bucle de
      fondo cada SIM_TICK_SECONDS. Nunca lo mueve el LLM (simulacion por codigo).
- [x] La prosperidad hace crecer la plaza FISICAMENTE (faroles, jardines, bancos y puestos
      de mercado que se construyen solos con el tiempo), no solo dinero (civic.txt, persistido).
- [x] Que crezcan aldeas ENTERAS, no solo la plaza: al llenarse una aldea (8 vecinos) se funda
      otra con nombre propio lejos (SettlementModule.foundNewTown). Poblacion acotada a 2..20.
- [ ] Ciudades grandes con barrios y varios gremios (futuro)

## Fase 9 - Estructuras sociales  (COMPLETA en su nucleo)
- [x] Parcelas reclamables por chunk, con PROPIETARIO, persistidas en la tabla plots.
      /claim (cuesta AET, integra la economia F6), /claim info, /unclaim.
- [x] PROTECCION: dentro de una parcela de otro nadie puede romper ni poner bloques
      (ClaimModule con cache en memoria chunk->propietario; cero red por bloque).
- [x] Validaciones en el backend: solape (409), fondos insuficientes (400, sin cobrar),
      propiedad al liberar (404 si no es tuya).
- [x] Gobierno de aldea: cada pueblo tiene ALCALDE (el vecino mas veterano) con su cartel en
      la plaza; se anuncia en la cronica al relevo (evento gobierno). Los recien llegados toman
      el oficio que le falta a la aldea (equilibrio de oficios).
- [ ] Ciudades y contratos formales entre jugadores (futuro; tablas cities/contracts ya
      existen para apoyarlo)

## Mejoras transversales  (EN CURSO)
- [x] Seguridad: filtro de contenido en respuestas de NPC (sanitize_chat_text tambien a la
      salida del LLM) + rate-limit por jugador (5 llamadas/10 s -> protege la cartera)
- [ ] NPC con aspecto HUMANO real (skins) via Citizens o packets (hoy son aldeanos)
- [x] Memoria a largo plazo (migracion 0004): ficha evolutiva del jugador que condensa lo
      viejo (concentra muchas charlas en un perfil), poda los turnos ya resumidos; corto
      plazo verbatim (~10 turnos) + largo plazo difuso. La IA nunca se satura.
- [x] Backups (DB + mundos) reproducibles: `scripts/backup.ps1` (pg_dump comprimido +
      tar de mundos, conserva N). Falta programarlo (cron/Task Scheduler) y off-site.
- [ ] Monitorizacion/logs y CI que compile el plugin Java (futuro)
- [ ] Prueba de login Bedrock (Geyser) y salto entre mundos con cliente real
## Ideas pendientes de estudio (sin implementar)

### Inversion del JUGADOR en la aldea (bucle de capital, sin comandos)
Idea del dueno (2026-07-27). El jugador puede **aportar** a la economia del pueblo y
recuperarlo con creces mas adelante:
- **Aportar** recursos (items en el granero) o AET, *sin comandos*: un **punto fisico de
  donacion** (un cofre/barril rotulado en el granero o en la plaza) o **dar dinero al
  alcalde** (clic derecho sobre el, como el mercader del mercado).
- Efecto: la aportacion es **capital** de la aldea -> mas produccion, crece mas rapido
  (mas habitantes, mas edificios). A corto plazo el jugador pierde; a largo, el excedente
  fisico del granero es suyo para recoger.
- **Contrapeso**: si el jugador **saca demasiado** del granero, descapitaliza la aldea y
  esta **decrece** (menos produccion -> menos prosperidad -> emigracion). Sacar el
  excedente es sostenible; vaciar el granero, no.
- Requiere: distinguir excedente vs. reservas del pueblo, y que el saqueo tenga efecto
  economico real (hoy el granero es solo un barril con items).
- Cimientos ya puestos por #11: produccion FISICA real que alimenta la economia
  (`/v1/production`) y upkeep por habitante (el pueblo vive de lo que trabaja).
