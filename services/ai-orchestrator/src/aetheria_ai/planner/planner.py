"""Generación de planes.

Fase 0: implementación determinista (stub) que produce un plan mínimo y seguro sin
llamar al LLM, para que el sistema sea ejecutable de extremo a extremo sin API key. El
punto de enganche del LLM (Nivel 3) está marcado abajo: en Fase 3, el LLM produce las
acciones propuestas y ESTE plan sigue pasando por el validador antes de devolverse.
"""

from __future__ import annotations

from aetheria_ai.models.plan import (
    ActionType,
    Plan,
    PlanAction,
    PlanRequest,
)


async def build_plan(request: PlanRequest) -> Plan:
    """Construye un Plan (propuesta) para el objetivo dado.

    En Fase 3 esto llamará a `llm.factory.get_provider()` para que el LLM proponga las
    acciones a partir de los resúmenes del world-state. Sea cual sea el resultado del
    LLM, el Plan resultante SIEMPRE pasa por el validador (no aquí, sino en la capa de
    API) antes de llegar al plugin.
    """
    # --- STUB Fase 0: plan seguro y reversible ---
    actions = [
        PlanAction(
            type=ActionType.SAY,
            params={"text": f"Recibido el objetivo: {request.goal}"},
        )
    ]
    return Plan(actor=request.actor, actions=actions, reversible=True, estimated_cost=1)
