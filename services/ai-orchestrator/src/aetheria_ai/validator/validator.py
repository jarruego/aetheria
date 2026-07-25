"""Validador determinista de planes.

Esta es la barrera de seguridad del sistema. Es CÓDIGO, no un prompt: aunque el LLM
proponga algo peligroso, aquí se rechaza antes de que llegue al plugin.

Reglas actuales (Fase 0, se ampliarán con permisos reales y world-state):
  1. Toda acción debe pertenecer a la lista blanca (garantizado por el tipo ActionType).
  2. Se rechazan planes marcados como irreversibles (por ahora, hasta tener doble
     confirmación).
  3. Se aplican límites duros: nº de acciones y coste estimado.
"""

from __future__ import annotations

from dataclasses import dataclass

from aetheria_ai.models.plan import Plan, PlanResponse, PlanStatus

MAX_ACTIONS_PER_PLAN = 50
MAX_ESTIMATED_COST = 10_000


@dataclass(frozen=True)
class ValidationResult:
    ok: bool
    reason: str | None = None


def _check(plan: Plan) -> ValidationResult:
    if not plan.actions:
        return ValidationResult(False, "El plan no contiene acciones.")

    if len(plan.actions) > MAX_ACTIONS_PER_PLAN:
        return ValidationResult(
            False, f"Demasiadas acciones ({len(plan.actions)} > {MAX_ACTIONS_PER_PLAN})."
        )

    if plan.estimated_cost > MAX_ESTIMATED_COST:
        return ValidationResult(
            False, f"Coste estimado excede el límite ({plan.estimated_cost} > {MAX_ESTIMATED_COST})."
        )

    if not plan.reversible:
        return ValidationResult(
            False, "Acción irreversible: requiere doble confirmación (no soportada aún)."
        )

    # Nota: los tipos de acción ya están restringidos por la enum ActionType; cualquier
    # tipo desconocido falla al deserializar antes de llegar aquí.
    return ValidationResult(True)


def validate_plan(plan: Plan) -> PlanResponse:
    """Devuelve un PlanResponse aprobado o rechazado. Nunca ejecuta nada."""
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
