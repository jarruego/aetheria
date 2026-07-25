"""Tests del validador: la barrera de seguridad debe rechazar lo peligroso."""

from aetheria_ai.models.plan import (
    Actor,
    ActorType,
    ActionType,
    Plan,
    PlanAction,
    PlanStatus,
)
from aetheria_ai.validator.validator import (
    MAX_ACTIONS_PER_PLAN,
    validate_plan,
)

ACTOR = Actor(type=ActorType.NPC, id="test-npc")


def _plan(**kwargs) -> Plan:
    defaults = dict(
        actor=ACTOR,
        actions=[PlanAction(type=ActionType.SAY, params={"text": "hola"})],
        reversible=True,
        estimated_cost=1,
    )
    defaults.update(kwargs)
    return Plan(**defaults)


def test_valid_plan_is_approved():
    result = validate_plan(_plan())
    assert result.status is PlanStatus.APPROVED
    assert result.actions


def test_empty_plan_is_rejected():
    result = validate_plan(_plan(actions=[]))
    assert result.status is PlanStatus.REJECTED
    assert result.actions == []


def test_irreversible_plan_is_rejected():
    result = validate_plan(_plan(reversible=False))
    assert result.status is PlanStatus.REJECTED
    assert "irreversible" in (result.rejection_reason or "").lower()


def test_too_many_actions_is_rejected():
    actions = [PlanAction(type=ActionType.SAY, params={"text": "x"})] * (
        MAX_ACTIONS_PER_PLAN + 1
    )
    result = validate_plan(_plan(actions=actions))
    assert result.status is PlanStatus.REJECTED


def test_excessive_cost_is_rejected():
    result = validate_plan(_plan(estimated_cost=999_999))
    assert result.status is PlanStatus.REJECTED
