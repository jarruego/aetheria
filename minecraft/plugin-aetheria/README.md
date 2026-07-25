# Plugin Aetheria (Java / Paper)

El unico componente que **ejecuta** cambios en el mundo. La IA propone; el plugin dispone.

## Que hace hoy

- `/aetheria ask <mensaje>`: conversa con un NPC (el backend decide el nivel 1/2/3; sin
  coste por defecto).
- `/aetheria npc <spawn|remove> [clave]`: crea/elimina un NPC como **entidad real**
  (Villager persistente) en tu posicion.
- `/sethome` / `/home`: guarda tu posicion y te teletransporta a tu casa (en los mundos
  de juego; persistencia local v1, migrara a la base de datos con economia/parcelas).
- `/aetheria plan <objetivo>`: pide un plan; si viene **aprobado** por el validador,
  ejecuta sus acciones de la **lista blanca** sobre el mundo real:
  - `SAY` — el NPC habla.
  - `MOVE_TO` — el NPC hace *pathfinding* hacia el jugador.
  - `GIVE_ITEM` — entrega items (material real, cantidad acotada).
  - `PLACE_BLUEPRINT` — coloca una estructura de un catalogo acotado (`platform`,
    `fountain`) unos bloques por delante.
  - `OPEN_TRADE` — abre un mercader con ofertas.

El objetivo determina las acciones (heuristica por palabras clave en el backend, sin
coste). Ej.: *"construye una fuente"* -> `PLACE_BLUEPRINT`; *"dame pan y ven"* ->
`GIVE_ITEM` + `MOVE_TO`.

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

Stack: Java 21, API de Paper 1.21.4, `java.net.http.HttpClient` + Gson (del servidor).
