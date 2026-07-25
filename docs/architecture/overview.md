# Arquitectura general

Aetheria es una plataforma de microservicios. Minecraft es solo la **capa de
presentación**: toda la lógica importante vive en el backend, y la persistencia en la
base de datos. Nada crítico depende de archivos del servidor de Minecraft.

## Vista de componentes

```
                         ┌─────────────────────────────┐
   Jugadores (Java) ────▶│          VELOCITY           │  Único punto de entrada
   Jugadores (Bedrock)──▶│   (Geyser + Floodgate)      │  Nunca se conecta a Paper
                         └──────────────┬──────────────┘  directamente
                                        │ (modern forwarding)
                        ┌───────────────┴───────────────┐
                        ▼                               ▼
                 ┌─────────────┐                 ┌─────────────┐
                 │   LOBBY     │                 │    MAIN     │  Mundo persistente:
                 │  (Paper)    │                 │  (Paper)    │  economía, ciudades,
                 │ sin economía│                 │ + plugin    │  parcelas, NPC, IA
                 └─────────────┘                 └──────┬──────┘
                                                        │ HTTP (contrato OpenAPI)
                                                        ▼
                                              ┌───────────────────┐
                                              │    API GATEWAY    │  REST, auth,
                                              │     (FastAPI)     │  rate-limit
                                              └─────────┬─────────┘
                                    ┌───────────────────┼───────────────────┐
                                    ▼                   ▼                   ▼
                          ┌─────────────────┐  ┌────────────────┐  ┌──────────────┐
                          │ AI ORCHESTRATOR │  │  WORLD-STATE   │  │   SUPABASE   │
                          │ planner+valida- │  │  resúmenes del │  │ (PostgreSQL) │
                          │ dor+LLM adapter │  │     mundo      │  │ persistencia │
                          └────────┬────────┘  └────────────────┘  └──────────────┘
                                   │ (adaptador desacoplado)
                                   ▼
                        ┌──────────────────────┐
                        │  Proveedor LLM        │  claude | openai | local
                        │  (intercambiable)     │
                        └──────────────────────┘
```

## Responsabilidades por servicio

| Servicio | Responsabilidad | Qué NO hace |
|---|---|---|
| **Velocity** | Enrutado de jugadores, punto de entrada, forwarding seguro | No lógica de juego |
| **Lobby (Paper)** | Bienvenida, tutorial, noticias, portales, estado de servidores | No economía ni inventarios importantes |
| **Main (Paper) + plugin** | Ejecutar planes validados, mover NPC, pathfinding, horarios | No decide nada por IA; no llama al LLM directo |
| **API Gateway** | Contrato REST, autenticación servicio-a-servicio, rate-limit | No lógica de IA |
| **AI Orchestrator** | Planificación, validación, adaptador LLM, conversación 3 niveles | No toca el mundo; no escribe en Minecraft |
| **World-State** | Mantener resúmenes estructurados del mundo (no bloques) | No almacena bloques crudos |
| **Supabase** | Toda la persistencia (jugadores, economía, NPC, memoria...) | No lógica |

## Reglas de oro

1. **La IA nunca modifica el mundo directamente.** Produce planes; el plugin ejecuta.
2. **Todo entra por Velocity.** Paper nunca es accesible directamente desde Internet.
3. **El backend no envía bloques al LLM.** Solo resúmenes estructurados (parcelas,
   ciudades, propietarios, economía, inventarios).
4. **Cada servicio es independiente.** Hoy comparten máquina; mañana pueden separarse
   sin cambiar el código, solo la configuración de red.
5. **La conversación tiene 3 niveles** para no gastar tokens en lo trivial (ver
   `security-flow.md` y ADR-0004).

## Decisiones registradas

Toda decisión estructural se documenta como ADR en `docs/adr/`. Empezar por ADR-0001.
