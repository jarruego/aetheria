"""Ruta pública de planes. Reenvía al AI Orchestrator, que devuelve el plan YA validado.

El gateway nunca ejecuta acciones ni las inventa: solo transporta un plan aprobado o
rechazado hacia el plugin.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from aetheria_api.client import post_orchestrator
from aetheria_api.schemas import (
    PlanRequest,
    PlanResponse,
    ServiceRequest,
    ServiceResponse,
)
from aetheria_api.security import require_internal_token

router = APIRouter(prefix="/v1", tags=["planning"])


@router.post(
    "/plans",
    response_model=PlanResponse,
    dependencies=[Depends(require_internal_token)],
)
async def create_plan(request: PlanRequest) -> PlanResponse:
    data = await post_orchestrator("/internal/plans", request.model_dump())
    return PlanResponse(**data)


@router.post(
    "/service",
    response_model=ServiceResponse,
    dependencies=[Depends(require_internal_token)],
)
async def create_service(request: ServiceRequest) -> ServiceResponse:
    """Servicio inteligente de PAGO. El orchestrator valida el plan y cobra en AET.

    El gateway solo transporta: no ejecuta ni inventa acciones.
    """
    data = await post_orchestrator("/internal/service", request.model_dump())
    return ServiceResponse(**data)
