"""Endpoints internos del AI Orchestrator.

Los consume el API Gateway, no el plugin directamente. La salida de /internal/plans ya
viene validada (aprobada o rechazada): el mundo nunca recibe un plan sin validar.
"""

from __future__ import annotations

from fastapi import APIRouter

from aetheria_ai.conversation import handle_conversation
from aetheria_ai.models.plan import (
    ActionType,
    Actor,
    ActorType,
    ConversationRequest,
    ConversationResponse,
    PlanRequest,
    PlanResponse,
    PlanStatus,
    ServiceRequest,
    ServiceResponse,
)
from aetheria_ai.planner.planner import build_plan
from aetheria_ai.validator.validator import validate_plan
from aetheria_ai.world_state_client import charge_player, record_plan_audit

router = APIRouter(prefix="/internal", tags=["internal"])

# Precio POR LO QUE SE CONSTRUYE (AET). El servicio cobra segun el resultado, no una tarifa
# plana: una casa cuesta mas que una fuente. Si el plan no construye nada, no se cobra.
_BLUEPRINT_PRICE = {"house": 120.0, "fountain": 30.0, "platform": 15.0}
_DEFAULT_BLUEPRINT_PRICE = 25.0


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


@router.post("/service", response_model=ServiceResponse)
async def service(request: ServiceRequest) -> ServiceResponse:
    """Servicio inteligente de PAGO: la IA construye/actua y se cobra en AET.

    Orden importante: primero se valida el plan (gratis de calcular) y SOLO si es
    aprobado se cobra. Asi el jugador nunca paga por un plan que el validador rechaza.
    """
    plan = await build_plan(
        PlanRequest(
            actor=Actor(type=ActorType.NPC, id=request.service),
            goal=request.description,
            world=request.world,
        )
    )
    result = validate_plan(plan)
    await record_plan_audit({
        "plan_id": str(result.plan_id),
        "actor_type": "service",
        "actor_id": request.service,
        "status": result.status.value,
        "rejection_reason": result.rejection_reason,
        "actions": [{"type": a.type.value, "params": a.params} for a in result.actions],
    })

    if result.status is not PlanStatus.APPROVED:
        return ServiceResponse(
            status=PlanStatus.REJECTED,
            reason=result.rejection_reason or "No se pudo realizar el servicio.",
        )

    # Se cobra SOLO por lo que se construye. Si no hay nada que construir, no se cobra nada.
    blueprints = [
        a.params.get("blueprint")
        for a in result.actions
        if a.type is ActionType.PLACE_BLUEPRINT
    ]
    if not blueprints:
        return ServiceResponse(
            status=PlanStatus.REJECTED,
            reason=("No he sabido que construirte con eso, asi que no te cobro nada. "
                    "Prueba a pedir 'una casa', 'una fuente' o 'una plataforma'."),
        )

    price = round(sum(_BLUEPRINT_PRICE.get(b, _DEFAULT_BLUEPRINT_PRICE) for b in blueprints), 2)
    if not await charge_player(request.player_uuid, price, f"servicio {request.service}"):
        return ServiceResponse(
            status=PlanStatus.REJECTED,
            reason=f"Fondos insuficientes: esto cuesta {price:.0f} AET.",
        )

    return ServiceResponse(status=PlanStatus.APPROVED, charged=price, actions=result.actions)
