"""Validador determinista de planes.

Esta es la barrera de seguridad del sistema. Es CODIGO, no un prompt: aunque el LLM
proponga algo peligroso, aqui se rechaza antes de que llegue al plugin.

Reglas:
  1. Toda accion debe pertenecer a la lista blanca (garantizado por el tipo ActionType).
  2. El texto que ira al chat se SANEA antes de validarlo (ver text_safety).
  3. Cada accion valida sus PARAMETROS (material/cantidad, blueprint permitido, etc.).
  4. Se rechazan planes irreversibles (hasta tener doble confirmacion).
  5. Limites duros: numero de acciones y coste estimado.

Orden importante: se sanea ANTES de validar. Asi, un texto que sea solo codigos de
formato queda vacio tras el saneo y lo rechaza la regla 'SAY sin texto' que ya existia,
sin necesidad de una regla nueva.
"""

from __future__ import annotations

from dataclasses import dataclass

from aetheria_ai.models.plan import ActionType, Plan, PlanAction, PlanResponse, PlanStatus
from aetheria_ai.validator.text_safety import sanitize_chat_text

MAX_ACTIONS_PER_PLAN = 50
MAX_ESTIMATED_COST = 10_000
MAX_GIVE_AMOUNT = 64

# Catalogos permitidos (deben reflejar los del plugin: defensa en profundidad en ambos lados).
ALLOWED_BLUEPRINTS = {"platform", "fountain", "house"}
ALLOWED_MOVE_TARGETS = {"player"}


@dataclass(frozen=True)
class ValidationResult:
    ok: bool
    reason: str | None = None


def _validate_action_params(action: PlanAction) -> str | None:
    p = action.params or {}

    if action.type is ActionType.SAY:
        if not isinstance(p.get("text"), str) or not p.get("text"):
            return "SAY sin texto"

    elif action.type is ActionType.GIVE_ITEM:
        material = p.get("material")
        amount = p.get("amount")
        if not isinstance(material, str) or not material:
            return "GIVE_ITEM sin material"
        if not isinstance(amount, int) or isinstance(amount, bool) or not (1 <= amount <= MAX_GIVE_AMOUNT):
            return f"GIVE_ITEM cantidad invalida (1..{MAX_GIVE_AMOUNT})"

    elif action.type is ActionType.MOVE_TO:
        if p.get("target") not in ALLOWED_MOVE_TARGETS:
            return "MOVE_TO destino no permitido"

    elif action.type is ActionType.PLACE_BLUEPRINT:
        if p.get("blueprint") not in ALLOWED_BLUEPRINTS:
            return f"blueprint no permitido: {p.get('blueprint')!r}"

    elif action.type is ActionType.OPEN_TRADE:
        offers = p.get("offers")
        if not isinstance(offers, list) or not offers:
            return "OPEN_TRADE sin ofertas"

    return None


def _check(plan: Plan) -> ValidationResult:
    if not plan.actions:
        return ValidationResult(False, "El plan no contiene acciones.")

    if len(plan.actions) > MAX_ACTIONS_PER_PLAN:
        return ValidationResult(
            False, f"Demasiadas acciones ({len(plan.actions)} > {MAX_ACTIONS_PER_PLAN})."
        )

    if plan.estimated_cost > MAX_ESTIMATED_COST:
        return ValidationResult(
            False, f"Coste estimado excede el limite ({plan.estimated_cost} > {MAX_ESTIMATED_COST})."
        )

    if not plan.reversible:
        return ValidationResult(
            False, "Accion irreversible: requiere doble confirmacion (no soportada aun)."
        )

    for action in plan.actions:
        reason = _validate_action_params(action)
        if reason is not None:
            return ValidationResult(False, reason)

    # Los tipos de accion ya estan restringidos por la enum ActionType; cualquier tipo
    # desconocido falla al deserializar antes de llegar aqui.
    return ValidationResult(True)


def _sanitized_actions(actions: list[PlanAction]) -> list[PlanAction]:
    """Copia las acciones saneando el texto que acabaria en el chat del juego.

    Devuelve objetos NUEVOS: el plan original no se modifica, para que quede
    constancia de lo que se propuso frente a lo que se aprobo.
    """
    limpias: list[PlanAction] = []
    for action in actions:
        if action.type is ActionType.SAY and isinstance((action.params or {}).get("text"), str):
            params = dict(action.params)
            params["text"] = sanitize_chat_text(params["text"])
            limpias.append(PlanAction(type=action.type, params=params))
        else:
            limpias.append(action)
    return limpias


def validate_plan(plan: Plan) -> PlanResponse:
    """Devuelve un PlanResponse aprobado o rechazado. Nunca ejecuta nada."""
    # SANEAR ANTES DE VALIDAR: lo que se valida es exactamente lo que se ejecutara.
    plan = plan.model_copy(update={"actions": _sanitized_actions(plan.actions)})

    result = _check(plan)
    if result.ok:
        return PlanResponse(
            plan_id=plan.plan_id,
            status=PlanStatus.APPROVED,
            actions=plan.actions,
            reversible=plan.reversible,
            estimated_cost=plan.estimated_cost,
        )
    return PlanResponse(
        plan_id=plan.plan_id,
        status=PlanStatus.REJECTED,
        rejection_reason=result.reason,
        actions=[],
        reversible=plan.reversible,
        estimated_cost=plan.estimated_cost,
    )
