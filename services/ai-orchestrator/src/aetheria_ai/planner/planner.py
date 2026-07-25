"""Generacion de planes con contexto del world-state y acciones fisicas (Fase 3+).

El backend describe la INTENCION de forma simbolica (p.ej. target="player",
blueprint="fountain"); el plugin resuelve el mundo real (entidades, coordenadas). Asi el
backend no necesita conocer coordenadas ni entidades concretas.

Coste cero por defecto: la seleccion de acciones es determinista (heuristica por
palabras clave), sin LLM. El punto de enganche del LLM sigue disponible; su salida, si se
activa, tambien pasaria por el validador.
"""

from __future__ import annotations

import re

from aetheria_ai.models.plan import ActionType, Plan, PlanAction, PlanRequest
from aetheria_ai.world_state_client import get_world_summary

# Emparejado por palabra completa (tokens), no por subcadena, para evitar falsos
# positivos como que "vender" dispare "ven".
_MOVE_WORDS = {"ven", "sigueme", "sígueme", "muevete", "muévete", "acercate", "acércate"}
_GIVE_WORDS = {"dame", "regalo", "regalame", "item", "pan", "comida"}
_BUILD_WORDS = {"plaza", "fuente", "construye", "construir", "edificio", "blueprint"}
_TRADE_WORDS = {"comercio", "comerciar", "trato", "trade", "vender", "tienda", "mercado"}


def _tokens(text: str) -> set[str]:
    return set(re.findall(r"[a-záéíóúñ]+", text.lower()))


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


def _actions_for_goal(goal: str, context: str) -> list[PlanAction]:
    words = _tokens(goal)
    actions: list[PlanAction] = []

    if words & _MOVE_WORDS:
        actions.append(PlanAction(type=ActionType.MOVE_TO, params={"target": "player"}))

    if words & _GIVE_WORDS:
        actions.append(
            PlanAction(type=ActionType.GIVE_ITEM, params={"material": "BREAD", "amount": 3})
        )

    if words & _BUILD_WORDS:
        blueprint = "fountain" if ({"fuente", "plaza"} & words) else "platform"
        actions.append(
            PlanAction(type=ActionType.PLACE_BLUEPRINT, params={"blueprint": blueprint})
        )

    if words & _TRADE_WORDS:
        actions.append(
            PlanAction(
                type=ActionType.OPEN_TRADE,
                params={"offers": [{"give": "EMERALD", "amount": 1, "for": "WHEAT", "for_amount": 20}]},
            )
        )

    # Siempre cerramos con una SAY que referencia el contexto real del mundo.
    actions.append(
        PlanAction(
            type=ActionType.SAY,
            params={"text": f"Objetivo: {goal}. Contexto del mundo: {context}."},
        )
    )
    return actions


async def build_plan(request: PlanRequest) -> Plan:
    # 1) Contexto estructurado del mundo (barato, sin bloques, sin LLM).
    summary = await get_world_summary(request.world)
    context = _context_text(summary)

    # --- Punto de enganche del LLM (opcional, de pago): podria proponer acciones ---
    # Sea cual sea el resultado, el Plan pasa por el validador antes de ejecutarse.

    # 2) Acciones deterministas segun el objetivo (coste cero).
    actions = _actions_for_goal(request.goal, context)

    return Plan(actor=request.actor, actions=actions, reversible=True, estimated_cost=len(actions))
