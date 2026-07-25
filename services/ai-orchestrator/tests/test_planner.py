"""Tests del planner con contexto del world-state y validacion."""

import asyncio

from aetheria_ai.models.plan import Actor, ActorType, PlanRequest, PlanStatus
from aetheria_ai.planner.planner import build_plan
from aetheria_ai.validator.validator import validate_plan


def test_plan_is_built_and_approved():
    req = PlanRequest(
        actor=Actor(type=ActorType.NPC, id="arquitecto-01"),
        goal="construir una plaza",
        world="main",
    )
    plan = asyncio.run(build_plan(req))
    result = validate_plan(plan)

    assert result.status is PlanStatus.APPROVED
    assert result.actions
    # world-state no esta disponible en el test -> el planner degrada con contexto de
    # fallback, pero SIEMPRE referencia el contexto del mundo en el plan.
    assert "Contexto del mundo" in result.actions[0].params["text"]
    assert plan.reversible is True
