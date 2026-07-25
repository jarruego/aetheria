# Guía de contribución

## Principios

- **Commits pequeños y atómicos.** Un cambio, un commit. Mensajes claros en imperativo.
- **Código documentado.** Docstrings en módulos y funciones no triviales.
- **Docker siempre que sea posible.** Nada se instala a mano.
- **Decisiones estructurales = ADR** en `docs/adr/` (ver ADR-0001).
- **La seguridad no se negocia:** ningún camino de código permite que la IA ejecute
  acciones sin pasar por el validador.

## Flujo

1. Rama por cambio (`feat/...`, `fix/...`, `docs/...`).
2. `ruff check` y `pytest` en verde antes de abrir PR.
3. CI (GitHub Actions) valida lint, tests y el contrato OpenAPI.

## Estilo

- Python 3.12, tipado, `ruff` (línea 100).
- Nombres y comentarios coherentes con el código existente.
