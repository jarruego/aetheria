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

Fase 0 - Fundacion (en curso). Ver docs/roadmap.md para el plan por fases.

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