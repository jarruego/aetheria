"""Endpoints internos del AI Orchestrator.

Los consume el API Gateway, no el plugin directamente. La salida de /internal/plans ya
viene validada (aprobada o rechazada): el mundo nunca recibe un plan sin validar.
"""

from __future__ import annotations

from fastapi import APIRouter

from aetheria_ai.conversation import handle_conversation
from aetheria_ai.models.plan import (
    ConversationRequest,
    ConversationResponse,
    PlanRequest,
    PlanResponse,
)
from aetheria_ai.planner.planner import build_plan
from aetheria_ai.validator.validator import validate_plan
from aetheria_ai.world_state_client import record_plan_audit

router = APIRouter(prefix="/internal", tags=["internal"])


@router.post("/conversation", response_model=ConversationResponse)
async def conversation(request: ConversationRequest) -> ConversationResponse:
    return await handle_conversation(request)


@router.post("/plans", response_model=PlanResponse)
async def plans(request: PlanRequest) -> PlanResponse:
    # 1) La IA PROPONE un plan.
    plan = await build_plan(request)
    # 2) El validador determinista APRUEBA o RECHAZA. Nunca se ejecuta aquí.
    result = validate_plan(plan)
    # 3) Fase 5: queda registrado en la auditoria (aprobado o rechazado).
    await record_plan_audit({
        "plan_id": str(result.plan_id),
        "actor_type": request.actor.type.value,
        "actor_id": request.actor.id,
        "status": result.status.value,
        "rejection_reason": result.rejection_reason,
        "actions": [{"type": a.type.value, "params": a.params} for a in result.actions],
    })
    return result
