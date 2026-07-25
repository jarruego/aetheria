"""Generacion de planes, ahora con contexto real del world-state (Fase 3).

Flujo: trae el resumen estructurado del mundo (world-state) -> construye un Plan
(propuesta) que referencia ese contexto. El Plan SIEMPRE pasa por el validador (en la
capa de API) antes de llegar al plugin.

Coste cero por defecto: no se llama a ningun LLM aqui. El punto de enganche para que un
LLM proponga acciones esta marcado abajo; cuando se active, su salida seguira pasando
por el validador (nunca ejecuta nada directamente).
"""

from __future__ import annotations

from aetheria_ai.models.plan import ActionType, Plan, PlanAction, PlanRequest
from aetheria_ai.world_state_client import get_world_summary


def _context_text(summary: dict | None) -> str:
    if not summary:
        return "sin contexto del world-state (servicio no disponible)"
    return (
        f"{summary.get('display_name', '?')}: "
        f"{summary.get('cities', 0)} ciudades, "
        f"{summary.get('plots', 0)} parcelas "
        f"({summary.get('plots_owned', 0)} con dueno), "
        f"{summary.get('npcs', 0)} NPC, "
        f"{summary.get('players_total', 0)} jugadores"
    )


async def build_plan(request: PlanRequest) -> Plan:
    # 1) Contexto estructurado del mundo (barato, sin bloques, sin LLM).
    summary = await get_world_summary(request.world)
    context = _context_text(summary)

    # --- Punto de enganche del LLM (Fase 3+, opcional y de pago) ---
    # Aqui un proveedor real podria PROPONER acciones a partir de `context` y `goal`.
    # Sea cual sea el resultado, el Plan pasa por el validador antes de ejecutarse.

    # 2) Plan determinista, seguro y reversible, que referencia el contexto real.
    actions = [
        PlanAction(
            type=ActionType.SAY,
            params={
                "text": f"Objetivo recibido: {request.goal}. Contexto del mundo: {context}."
            },
        )
    ]
    return Plan(actor=request.actor, actions=actions, reversible=True, estimated_cost=1)
