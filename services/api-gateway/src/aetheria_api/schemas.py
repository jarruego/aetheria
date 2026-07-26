"""Esquemas públicos del API Gateway (espejo de contracts/openapi.yaml)."""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field


class Health(BaseModel):
    status: str = "ok"
    service: str = "api-gateway"
    version: str


class ConversationRequest(BaseModel):
    npc_id: str
    player_id: str
    message: str = Field(max_length=2000)
    world: str = "main"
    npc_name: str | None = Field(default=None, max_length=40)


class ConversationResponse(BaseModel):
    reply: str
    level: int


class ActorType(str, Enum):
    NPC = "npc"
    PLAYER = "player"
    SYSTEM = "system"


class Actor(BaseModel):
    type: ActorType
    id: str


class PlanRequest(BaseModel):
    actor: Actor
    goal: str
    world: str = "main"
    context_ref: str | None = None


class PlanAction(BaseModel):
    type: str
    params: dict = Field(default_factory=dict)


class PlanStatus(str, Enum):
    APPROVED = "approved"
    REJECTED = "rejected"


class PlanResponse(BaseModel):
    plan_id: str
    status: PlanStatus
    rejection_reason: str | None = None
    actions: list[PlanAction] = Field(default_factory=list)
    reversible: bool = True
    estimated_cost: int = 0


class ServiceRequest(BaseModel):
    """Un jugador contrata un servicio inteligente de PAGO (Arquitecto IA, etc.)."""

    player_uuid: str
    service: str = Field(max_length=40)
    description: str = Field(max_length=2000)
    world: str = "main"


class ServiceResponse(BaseModel):
    status: PlanStatus
    reason: str | None = None
    charged: float = 0.0
    actions: list[PlanAction] = Field(default_factory=list)
