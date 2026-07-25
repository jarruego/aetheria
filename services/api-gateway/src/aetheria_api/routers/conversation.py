"""Ruta pública de conversación. Reenvía al AI Orchestrator."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from aetheria_api.client import post_orchestrator
from aetheria_api.schemas import ConversationRequest, ConversationResponse
from aetheria_api.security import require_internal_token

router = APIRouter(prefix="/v1", tags=["conversation"])


@router.post(
    "/conversation",
    response_model=ConversationResponse,
    dependencies=[Depends(require_internal_token)],
)
async def conversation(request: ConversationRequest) -> ConversationResponse:
    data = await post_orchestrator("/internal/conversation", request.model_dump())
    return ConversationResponse(**data)
