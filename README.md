# Aetheria AI

> Un servidor de Minecraft persistente donde la IA no es un jugador: es el sistema operativo del mundo.

Aetheria no es "un servidor de Minecraft con un chatbot". Es una plataforma disenada
para evolucionar durante anos: una civilizacion persistente con economia, ciudades,
empresas, profesiones, gobiernos y NPC con memoria, gobernada parcialmente por IA a
traves de servicios backend desacoplados.

## Principios de diseno (no negociables)

1. La IA nunca toca el mundo directamente. Solo produce planes; un validador
   determinista los aprueba; el plugin ejecuta solo acciones de una lista blanca.
2. Todo pasa por Velocity. Nunca se conecta directamente a Paper.
3. La IA esta desacoplada. Cambiar Claude por OpenAI (u otro) no toca Minecraft.
4. Infrastructure as Code. Nada se instala a mano: Terraform + Docker + scripts.
5. Microservicios desde el dia uno. Aunque hoy todo viva en una sola maquina,
   nunca se asume que siempre habra una sola maquina.
6. La persistencia vive en la base de datos (Supabase), no en archivos YAML.

## Flujo de seguridad

```
Minecraft -> Plugin -> API Gateway -> AI Orchestrator -> Plan (JSON)
                                                            |
                                                        Validador
                                                            |
                                            Plugin (lista blanca) -> Minecraft
```

Un LLM nunca ejecuta comandos. Ver docs/architecture/security-flow.md.

## Estructura del monorepo

| Carpeta | Contenido |
|---|---|
| docs/ | Arquitectura y ADRs (decisiones de arquitectura) |
| contracts/ | Contrato de API (OpenAPI) entre plugin y backend |
| services/ai-orchestrator/ | Backend IA: world-model, planner, validador, adaptador LLM |
| services/api-gateway/ | API REST: punto de entrada del plugin al backend |
| services/world-state/ | Modelo del mundo (resumenes estructurados, no bloques) |
| db/supabase/ | Migraciones SQL versionadas |
| minecraft/ | Velocity, Lobby, Main y el plugin Java |
| infra/ | Terraform (Oracle Cloud) y Docker |
| scripts/ | Despliegue reproducible |

## Estado del proyecto

Fases 0-6 completas (verificado en local). Ver docs/roadmap.md para el detalle.

- **F0-F3 (backend + IA):** microservicios FastAPI, Postgres con migraciones versionadas,
  world-state, adaptador LLM intercambiable, conversacion en 3 niveles y el nucleo de
  seguridad: la IA propone un plan, un validador determinista lo aprueba/rechaza y el
  plugin solo ejecuta acciones de una lista blanca.
- **Red Minecraft:** Velocity como unico punto de entrada; perfiles lean (solo main) y
  full (lobby + main + creative). Bedrock via Geyser/Floodgate. Plugin Java (Paper) que se
  compila y despliega solo con docker compose.
- **F5 (el mundo recuerda):** jugadores, casas y memoria de NPC persisten en la DB. Los
  NPC tienen persona humana y memoria en dos capas (turnos recientes verbatim + ficha
  evolutiva del jugador que condensa lo antiguo). Auditoria de cada plan de la IA.
- **F6 (economia y servicios IA):** moneda AET con cuentas y transferencias (`/balance`,
  `/pay`) y **servicios inteligentes de pago** (Arquitecto/Decorador/Urbanista): la IA
  construye por encargo y solo se cobra si el plan pasa el validador. Se venden servicios,
  nunca ventajas. Ver docs/adr/0011.
- **Siguiente:** F7 NPC vivos (rutinas/pathfinding), F8 el mundo evoluciona solo, F9
  estructuras sociales. F4 Cloud (Oracle/Terraform) pendiente de capacidad.

IA a coste cero por defecto (`LLM_PROVIDER=stub`); nivel 3 real gratis con Ollama local.

## Puesta en marcha (desarrollo local)

Requisitos: Docker, Python 3.12+, Git.

```
cp .env.example .env
docker compose up -d
```

Comprobar salud:

```
curl http://localhost:8080/health
curl http://localhost:8090/health
```

## Licencia

Proyecto privado. Todos los derechos reservados (por ahora).