# AI Orchestrator

Corazón de la IA de Aetheria. **Propone**, nunca ejecuta.

## Responsabilidades

- **Planner**: genera planes de acción (Fase 0: stub determinista; Fase 3: LLM).
- **Validador**: barrera de seguridad determinista; aprueba o rechaza planes.
- **Adaptador LLM**: interfaz `LLMProvider` desacoplada (claude/openai/local, ADR-0004).
- **Conversación 3 niveles**: enruta antes de gastar tokens.

## Estructura

```
src/aetheria_ai/
├── main.py            # App FastAPI + /health
├── config.py          # Settings desde entorno
├── conversation.py    # Enrutador de 3 niveles
├── api/routes.py      # /internal/conversation, /internal/plans
├── models/plan.py     # Plan, PlanAction (lista blanca), esquemas
├── planner/planner.py # Generación de planes
├── validator/         # Validador determinista (la barrera)
└── llm/               # base.py (interfaz) + anthropic_provider.py + factory.py
```

## Desarrollo

```bash
pip install -e ".[dev,anthropic]"
uvicorn aetheria_ai.main:app --app-dir src --port 8090 --reload
pytest
```

## Nota de seguridad

El endpoint `/internal/plans` **siempre** pasa el plan por el validador antes de
devolverlo. Ningún camino del código entrega un plan sin validar. Ver
`../../docs/architecture/security-flow.md`.
