"""Modelos del dominio: el 'Plan' es el contrato entre la IA y el mundo.

Un LLM produce un Plan (propuesta). El validador lo aprueba o rechaza. El plugin solo
ejecuta planes aprobados, y solo acciones de la lista blanca. Ver
`docs/architecture/security-flow.md`.
"""

from __future__ import annotations

from enum import Enum
from uuid import UUID, uuid4

from pydantic import BaseModel, Field


class ActorType(str, Enum):
    NPC = "npc"
    PLAYER = "player"
    SYSTEM = "system"


class Actor(BaseModel):
    type: ActorType
    id: str


class ActionType(str, Enum):
    """Lista blanca de acciones ejecutables. NADA fuera de esto llega al mundo.

    Ampliar esta enum es una decisión deliberada y revisable (idealmente con su ADR).
    """

    SAY = "SAY"
    MOVE_TO = "MOVE_TO"
    PLACE_BLUEPRINT = "PLACE_BLUEPRINT"
    GIVE_ITEM = "GIVE_ITEM"
    OPEN_TRADE = "OPEN_TRADE"


class PlanAction(BaseModel):
    type: ActionType
    params: dict = Field(default_factory=dict)


class PlanStatus(str, Enum):
    APPROVED = "approved"
    REJECTED = "rejected"


class PlanRequest(BaseModel):
    actor: Actor
    goal: str
    world: str = "main"
    context_ref: str | None = None


class Plan(BaseModel):
    """Plan propuesto, antes de validar."""

    plan_id: UUID = Field(default_factory=uuid4)
    actor: Actor
    actions: list[PlanAction] = Field(default_factory=list)
    reversible: bool = True
    estimated_cost: int = 0


class PlanResponse(BaseModel):
    """Resultado de validar un plan. Es lo único que recibe el plugin."""

    plan_id: UUID
    status: PlanStatus
    rejection_reason: str | None = None
    actions: list[PlanAction] = Field(default_factory=list)
    reversible: bool = True
    estimated_cost: int = 0


class ConversationRequest(BaseModel):
    npc_id: str
    player_id: str
    message: str
    world: str = "main"


class ConversationResponse(BaseModel):
    reply: str
    level: int  # 1=código, 2=modelo local, 3=LLM potente
