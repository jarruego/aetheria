# ADR-0002: Python + FastAPI para los servicios backend

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

El backend orquesta IA (planificación, validación, adaptadores LLM, modelos locales
para el Nivel 2 de conversación) y expone una API REST al plugin. Necesitamos un stack
con ecosistema de IA/ML maduro, buen tipado, y despliegue sencillo en contenedores.

Alternativas consideradas: TypeScript + NestJS, Go.

## Decisión

**Python 3.12 + FastAPI** para todos los servicios backend (api-gateway,
ai-orchestrator, world-state).

Razones:

- Ecosistema de IA/LLM nativo (SDKs oficiales, modelos locales, tooling ML) — crítico
  para los 3 niveles de conversación y la orquestación de IA.
- FastAPI da tipado con Pydantic, validación de esquemas y OpenAPI autogenerado, lo que
  encaja con nuestro contrato `contracts/openapi.yaml`.
- Contenedores ligeros y arranque rápido.

El **plugin de Minecraft es Java** (requisito de Paper) y vive aparte en `minecraft/`;
se comunica con el backend solo por HTTP a través del contrato OpenAPI. No hay
acoplamiento de lenguaje entre plugin y backend.

## Consecuencias

- (+) Máxima palanca para la parte de IA, que es el corazón del proyecto.
- (+) Un solo lenguaje backend reduce carga cognitiva del equipo.
- (-) Python no es tan rápido como Go para I/O puro; se mitiga con async y con separar
  servicios calientes si algún día hace falta (los microservicios lo permiten).
- El plugin Java y el backend Python evolucionan por separado (ver ADR-0004 para el
  desacople de proveedores LLM).
