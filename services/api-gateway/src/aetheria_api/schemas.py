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
