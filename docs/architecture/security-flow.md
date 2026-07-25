# Flujo de seguridad: cómo la IA actúa sin poder romper el mundo

El principio más importante de Aetheria: **un LLM nunca ejecuta acciones**. Solo
propone. Entre la propuesta y el mundo hay una barrera determinista que ningún modelo
puede saltarse.

## El flujo completo

```
1. Minecraft        Un jugador interactúa con un NPC / dispara un evento
       │
2. Plugin           Captura el evento y lo envía como datos estructurados
       │            (nunca comandos)
       ▼
3. API Gateway      Autentica, valida el esquema, aplica rate-limit
       │
4. AI Orchestrator  Construye contexto (resúmenes del world-state, memoria del NPC)
       │
5. LLM              Produce un PLAN en JSON (una lista de acciones propuestas).
       │            El LLM NO tiene acceso a ejecutar nada.
       ▼
6. Validador        Comprueba el plan contra reglas deterministas:
       │              - ¿cada acción está en la lista blanca?
       │              - ¿el actor tiene permisos sobre esa parcela?
       │              - ¿respeta límites (tamaño, coste, cantidad)?
       │              - ¿es reversible? (se rechaza lo irreversible)
       │            Si algo falla -> plan RECHAZADO. No hay ejecución parcial.
       ▼
7. Plugin           Recibe SOLO un plan aprobado y ejecuta acciones de la lista
       │            blanca, una a una, con logging y capacidad de rollback.
       ▼
8. Minecraft        El mundo cambia de forma controlada y auditable.
```

## Qué está prohibido (por diseño, no por convención)

- ❌ Ejecución libre de comandos por parte del LLM.
- ❌ Modificación directa de archivos del mundo por cualquier servicio de IA.
- ❌ Acciones irreversibles sin doble confirmación.
- ❌ Enviar bloques crudos al LLM (solo resúmenes).

## El "Plan" como contrato

Un plan es un documento JSON versionado con esta forma (ver
`services/ai-orchestrator/src/aetheria_ai/models/plan.py`):

```json
{
  "plan_id": "uuid",
  "actor": { "type": "npc", "id": "arquitecto-01" },
  "context_ref": "world-state snapshot id",
  "actions": [
    { "type": "PLACE_BLUEPRINT", "params": { "...": "..." } },
    { "type": "SAY", "params": { "text": "..." } }
  ],
  "reversible": true,
  "estimated_cost": 120
}
```

El validador es **código determinista y testeado**, no un prompt. Aunque el LLM
"alucine" una acción peligrosa, el validador la rechaza antes de que llegue al plugin.

## Los 3 niveles de conversación

Para no gastar tokens en lo trivial (ADR-0004):

- **Nivel 1 — Código, sin IA:** FAQ, horarios, saludos, precios, estado de pedidos.
- **Nivel 2 — Modelo local pequeño:** conversación normal, barata.
- **Nivel 3 — LLM potente:** solo cuando el caso lo justifica (razonamiento, planes).

El AI Orchestrator decide el nivel antes de gastar un solo token de un LLM caro.
