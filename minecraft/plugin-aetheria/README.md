# Plugin Aetheria (Java / Paper)

El unico componente que **ejecuta** cambios en el mundo. La IA propone; el plugin dispone.

## Que hace hoy

### Comandos `/aetheria`
- `/aetheria ask <mensaje>`: conversa con un NPC (el backend decide el nivel 1/2/3; sin
  coste por defecto).
- `/aetheria plan <objetivo>`: pide un plan; si viene **aprobado** por el validador,
  ejecuta sus acciones de la **lista blanca** sobre el mundo real (ver abajo).
- `/aetheria npc <spawn|remove> [clave]`: crea/elimina un NPC como **entidad real**
  (Villager persistente) en tu posicion.
- `/aetheria servicio <arquitecto|decorador|urbanista> <que quieres>`: encargo de PAGO a la
  IA; solo se cobra si el validador aprueba el plan.
- `/aetheria cronica`: te da la **Cronica de Aetheria** (un libro maquetado, lo mas reciente
  primero, con la vida del pueblo: nacimientos, bodas, muertes, fundaciones de aldea...).
- `/aetheria schem <list|paste <n>|save <n>>`: esquematicos FAWE (solo si FAWE/WorldEdit esta
  instalado; si no, avisa). Desde consola/RCON: `savecube`, `savecatalog`, `pastestreet`.

### Acciones de la lista blanca (`PlanExecutor.WHITELIST`)
- `SAY` — el NPC habla.
- `MOVE_TO` — el NPC hace *pathfinding* hacia el jugador.
- `GIVE_ITEM` — entrega items (material real, cantidad acotada).
- `PLACE_BLUEPRINT` — coloca una estructura de un catalogo acotado unos bloques por delante.
  Catalogo (`Blueprint`): `house`, `platform`, `fountain`, `garden`, `lamppost`, `statue`,
  `bigfountain`.
- `OPEN_TRADE` — abre un mercader con ofertas.

El objetivo determina las acciones (heuristica por palabras clave en el backend, sin
coste). Ej.: *"construye una fuente"* -> `PLACE_BLUEPRINT`; *"dame pan y ven"* ->
`GIVE_ITEM` + `MOVE_TO`.

### Otros comandos (mundos de juego)
- `/sethome` · `/home`: casa **persistida en la DB** (una por servidor); al entrar el
  jugador se registra en la DB (Fase 5).
- `/balance` · `/pay <jugador> <cantidad>`: economia AET (Fase 6).
- `/claim [comprar|alquilar] [pequena|mediana|grande]` · `/claim info` · `/unclaim`: parcelas
  reclamables con propietario y proteccion (Fase 9).
- `/arquitecto` · `/servicios` · `/decorador`: servicios guiados de PAGO (casa a medida,
  decoracion); cobran solo si construyen. `/deshacer`: revierte con reembolso (`UndoModule`).
- `/sell [all]` · `/worth` · `/shop`: mercado (`ShopModule`). `/guia`: relee el libro-guia.
- `/warp <destino>` · `/warps`: viaje rapido a plaza/mercado/taberna/spawn.

### Modulos (`AetheriaPlugin.onEnable`)
- **Mundo de juego (`main`)**: `VillageModule` (plaza fisica + reubicacion de spawn a bioma
  normal), `SettlementModule` (pueblo vivo procedural multi-aldea: colonos con
  genero/edad/oficio/familia, aldeas autofundadas con nombre y bienvenida, alcalde con panel
  holografico, granero, edificios de oficio permanentes, proteccion anti-creeper),
  `NpcRoutineModule` (rutina + pathfinding), `JobsModule`, `ShopModule`, `HudModule`,
  `ClaimModule`, `ArchitectModule`, `DecoratorModule`, `UndoModule`, `WarpModule`,
  `ConversationManager` (memoria por individuo), `ReturnPortalModule`.
- **Creativo (`creative`)**: en vez de la aldea viva, `CatalogModule` (galeria rotulada de todo
  lo que sabemos construir) + los comandos de esquematicos FAWE.
- **Lobby (`lobby`)**: `LobbyModule` (hub void con portales) + `LobbyGuideModule` (Aeon, el
  conserje unico).
- **Construccion compartida** (todos los caminos: aldea, arquitecto, decorador, blueprint,
  esquematicos): `TerrainPlanner` (nivelado columna a columna + pilotes sobre agua/hielo),
  `BuildRegistry` (registro persistente de cajas 3D en `regions.txt`; nadie pisa lo ya
  construido), `Blueprint` (catalogo de estructuras, `buildHouse`, `workplaceShowcase`).
- **Comun**: `SchematicModule` + `SchematicWriter` (esquematicos FAWE, solo si FAWE/WorldEdit
  esta presente), `PlayerSyncListener`.

## Rol del servidor (main vs lobby)

El plugin lee su rol de la variable de entorno `AETHERIA_ROLE` (o `role` en `config.yml`):

- **`main`** (por defecto): NPC, conversacion y ejecucion de planes.
- **`lobby`**: activa el **modulo lobby** — un hub tipico de Minecraft:
  - Mundo **void** (vacio; el servicio `lobby` usa `LEVEL_TYPE=minecraft:flat` +
    `GENERATOR_SETTINGS` con `layers:[]`), donde solo existe una **sala cerrada** de
    cuarzo con cristaleras que construye el plugin, flotando en el vacio.
  - Sala amplia (15x15) con columnas, grandes cristaleras al vacio y un **faro central
    con haz de luz**. Carteles orientados hacia el jugador.
  - **Portales** configurables (`lobby.portals`): por defecto **main** (esmeralda) y
    **creativo** (diamante). Al pisar uno, envia al jugador a ese servidor via el mensaje
    BungeeCord "Connect" de Velocity.
  - En los **mundos de juego** (rol != lobby), el plugin construye un **portal de vuelta
    al lobby** cerca del spawn (`return-portal` en config).
  - **NPC guias conversables** junto a cada portal (aldeanos, cada uno con **bioma +
    profesion distintos** = ropa/colores diferentes): clic derecho -> modo charla
    inmersivo (tu chat va solo al NPC, responde por la tuberia de 3 niveles; sales con
    "adios" o alejandote). `ConversationManager`. Cada NPC tiene **personalidad** (nombre +
    caracter) y **memoria**: recuerda lo que le cuentas (persistido en la DB, Fase 5).
  - Bienvenida + instrucciones al entrar; te teletransporta a la sala.
  - **Protecciones de hub**: modo aventura, invulnerable, no puedes morir, atacar,
    recibir dano ni pasar hambre; sin mobs; paz; hora y clima fijos. (Eventos cancelados
    + gamerules + `MODE=adventure`/`DIFFICULTY=peaceful`/`PVP=false` en el compose.)

Tambien funciona `/server main` · `/server lobby` (comando nativo de Velocity).

## Reglas (defensa en profundidad)

- ❌ Nunca llama a un LLM ni a la base de datos: solo al **API Gateway** (`/v1/...`) con
  `Authorization: Bearer <INTERNAL_SERVICE_TOKEN>`.
- ❌ Nunca ejecuta un plan con `status != approved`.
- ❌ Nunca ejecuta una accion fuera de la lista blanca del propio plugin
  (`PlanExecutor.WHITELIST`), aunque el backend ya valida.
- Todo el I/O de red es asincrono; las acciones sobre el mundo se ejecutan en el hilo
  principal via el scheduler de Bukkit.

## Configuracion (`config.yml`)

```yaml
gateway:
  url: "http://api-gateway:8080"     # nombre de servicio en la red docker
  token: "changeme-..."               # o variable de entorno INTERNAL_SERVICE_TOKEN
default-npc: "arquitecto-01"
```

## Compilar (sin instalar nada: contenedor Gradle)

```bash
cd minecraft/plugin-aetheria
docker run --rm -v "$PWD:/work" -w /work gradle:8.10.2-jdk21 gradle build --no-daemon
# jar -> build/libs/aetheria-plugin-0.1.0.jar
```

O con el wrapper incluido: `./gradlew build`.

## Probar contra la red local

1. Levanta el stack (`./scripts/dev-up.ps1`).
2. Copia el jar al servidor main y reinicialo:
   ```bash
   docker cp build/libs/aetheria-plugin-0.1.0.jar aetheria-main-1:/data/plugins/
   docker compose restart main
   ```
3. Entra al servidor, ve al mundo `main` (`/server main`) y prueba:
   `/aetheria ask hola` y `/aetheria plan construir una plaza`.

Stack: el plugin **compila a Java 21** (API de Paper 1.21.4), `java.net.http.HttpClient` +
Gson (del servidor). El **servidor** corre sobre la imagen `itzg/minecraft-server:java25`
(FAWE 2.15.3 exige Java 25; con la de Java 21 no arranca).
