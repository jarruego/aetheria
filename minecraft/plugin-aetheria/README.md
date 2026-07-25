# Plugin Aetheria (Java / Paper)

El unico componente que **ejecuta** cambios en el mundo. La IA propone; el plugin dispone.

## Que hace hoy

- `/aetheria ask <mensaje>`: conversa con un NPC (el backend decide el nivel 1/2/3; sin
  coste por defecto).
- `/aetheria npc <spawn|remove> [clave]`: crea/elimina un NPC como **entidad real**
  (Villager persistente) en tu posicion.
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
- **`lobby`**: activa el **modulo lobby** — construye un hub (plataforma de cuarzo +
  cartel + **portal de esmeralda**), da la bienvenida e instrucciones al entrar, y al
  pisar el portal envia al jugador a `main` mediante el mensaje BungeeCord "Connect" de
  Velocity (destino configurable en `lobby.portal-target`). En docker-compose, el
  servicio `lobby` (modo full) arranca con `AETHERIA_ROLE=lobby`.

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
