"""Esquemas de salida del World-State (resumenes, no bloques)."""

from __future__ import annotations

from pydantic import BaseModel


class WorldRef(BaseModel):
    key: str
    display_name: str
    persistent: bool


class WorldSummary(BaseModel):
    """Resumen estructurado de un mundo. Esto es lo que consume la IA como contexto."""

    world: str
    display_name: str
    cities: int
    plots: int
    plots_owned: int
    npcs: int
    players_total: int


# --- Escritura (Fase 5) ---

class PlayerUpsert(BaseModel):
    uuid: str
    username: str


class HomeUpsert(BaseModel):
    uuid: str
    username: str | None = None
    server: str
    world: str
    x: float
    y: float
    z: float
    yaw: float = 0.0
    pitch: float = 0.0


class HomeOut(BaseModel):
    server: str
    world: str
    x: float
    y: float
    z: float
    yaw: float
    pitch: float


class ConversationAppend(BaseModel):
    npc_key: str
    player_uuid: str
    role: str          # 'player' | 'npc'
    content: str


class ConversationTurn(BaseModel):
    role: str
    content: str


class SummaryUpsert(BaseModel):
    npc_key: str
    player_uuid: str
    summary: str


class SummaryOut(BaseModel):
    summary: str


# --- Economia (Fase 6) ---

class BalanceOut(BaseModel):
    balance: float
    currency: str = "AET"


class TransferIn(BaseModel):
    from_uuid: str
    to_uuid: str
    amount: float
    reason: str | None = None


class ChargeIn(BaseModel):
    uuid: str
    amount: float
    reason: str | None = None


class PlanAudit(BaseModel):
    plan_id: str
    actor_type: str
    actor_id: str
    status: str        # 'approved' | 'rejected'
    rejection_reason: str | None = None
    actions: list = []


# --- Cronica del mundo (Fase 8) ---

class WorldEventOut(BaseModel):
    kind: str          # 'economy' | 'market' | ...
    description: str
    created_at: str


# --- Estructuras sociales: parcelas (Fase 9) ---

class PlotClaimIn(BaseModel):
    owner_uuid: str
    owner_name: str | None = None
    world: str                 # clave del mundo ('main', 'creative'...)
    min_x: int
    min_z: int
    max_x: int
    max_z: int
    base_y: int = 64           # altura a la que se reclamo (centro de la banda protegida)
    rental: bool = False       # True = alquiler; False = compra fija


class PlotUnclaimIn(BaseModel):
    owner_uuid: str
    world: str
    min_x: int
    min_z: int


class PlotOut(BaseModel):
    owner_uuid: str | None = None
    owner_name: str | None = None
    world: str
    min_x: int
    min_z: int
    max_x: int
    max_z: int
    base_y: int = 64
    rental: bool = False
    rent: float = 0.0
