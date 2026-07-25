# ADR-0001: Registrar las decisiones de arquitectura como ADRs

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

Aetheria debe evolucionar durante años y sobrevivir a cambios de contexto (nuevas
personas, nuevos mundos, migraciones). Las decisiones tomadas hoy deben ser
comprensibles y cuestionables mañana, con su razón registrada.

## Decisión

Toda decisión estructural se documenta como un **Architecture Decision Record (ADR)**
en `docs/adr/`, numerado secuencialmente e inmutable una vez aceptado. Si una decisión
se revierte, no se borra: se crea un nuevo ADR que la supersede y se marca el anterior
como "Supersedida por ADR-XXXX".

Formato: Contexto, Decisión, Consecuencias.

## Consecuencias

- (+) Historia trazable de por qué el sistema es como es.
- (+) Onboarding más rápido.
- (-) Disciplina: hay que escribir el ADR antes de implementar cambios grandes.
