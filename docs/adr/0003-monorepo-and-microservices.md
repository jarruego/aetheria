# ADR-0003: Monorepo con servicios independientes desplegables por separado

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

Hoy jugamos dos personas y todo cabe en una máquina (Oracle Cloud Always Free). Pero el
requisito es escalar a miles de jugadores y a varias máquinas sin rediseñar. Debemos
evitar dos trampas opuestas: (a) un monolito que asume una sola máquina, y (b) una
constelación de repos difícil de coordinar siendo un equipo diminuto.

## Decisión

Un **monorepo** con **servicios independientes**:

- Cada servicio (`services/*`) tiene su propio `pyproject.toml`, `Dockerfile` y ciclo de
  vida. Se construye y despliega como contenedor separado.
- La comunicación entre servicios es siempre por red (HTTP/contrato), nunca por imports
  compartidos de lógica de negocio. **Nunca se asume memoria compartida ni una sola
  máquina.**
- El monorepo facilita cambios atómicos, un solo CI, y contratos versionados juntos.
- Migración futura a varias máquinas / Kubernetes = cambiar orquestación y red, no el
  código.

## Consecuencias

- (+) Escala horizontal sin reescritura: mover un servicio a otra máquina es
  configuración, no refactor.
- (+) Un solo sitio para issues, CI y contratos.
- (-) Hay que ser disciplinado y no crear acoplamientos ocultos entre servicios "porque
  están en el mismo repo". Regla: si dos servicios comparten lógica, se extrae a una
  librería publicada, no a un import relativo.
