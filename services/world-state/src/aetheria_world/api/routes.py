"""Endpoints internos del World-State: resumenes de solo lectura.

Los consume el API Gateway (y en Fase 3 el planner). Nunca devuelven bloques.
"""

from __future__ import annotations

import decimal
import json
import uuid

from fastapi import APIRouter, HTTPException

from aetheria_world.db import is_ready, pool
from aetheria_world.models import (
    BalanceOut,
    ChargeIn,
    ConversationAppend,
    ConversationTurn,
    HomeOut,
    HomeUpsert,
    PlanAudit,
    PlayerUpsert,
    SummaryOut,
    SummaryUpsert,
    TransferIn,
    WorldRef,
    WorldSummary,
)

router = APIRouter(prefix="/internal", tags=["world-state"])


def _require_db() -> None:
    if not is_ready():
        raise HTTPException(status_code=503, detail="Base de datos no disponible")


@router.get("/worlds", response_model=list[WorldRef])
async def worlds() -> list[WorldRef]:
    _require_db()
    rows = await pool().fetch(
        "select key, display_name, persistent from worlds order by key"
    )
    return [WorldRef(**dict(r)) for r in rows]


@router.get("/world/{key}/summary", response_model=WorldSummary)
async def world_summary(key: str) -> WorldSummary:
    _require_db()
    async with pool().acquire() as conn:
        w = await conn.fetchrow(
            "select id, key, display_name from worlds where key = $1", key
        )
        if w is None:
            raise HTTPException(status_code=404, detail="Mundo no encontrado")
        wid = w["id"]
        cities = await conn.fetchval("select count(*) from cities where world_id = $1", wid)
        plots = await conn.fetchval("select count(*) from plots where world_id = $1", wid)
        owned = await conn.fetchval(
            "select count(*) from plots where world_id = $1 and owner_id is not null", wid
        )
        npcs = await conn.fetchval("select count(*) from npcs where world_id = $1", wid)
        players = await conn.fetchval("select count(*) from players")
    return WorldSummary(
        world=w["key"],
        display_name=w["display_name"],
        cities=cities,
        plots=plots,
        plots_owned=owned,
        npcs=npcs,
        players_total=players,
    )


# --- Escritura (Fase 5): el mundo empieza a recordar ---

_UPSERT_PLAYER = """
    insert into players (java_uuid, username)
    values ($1, $2)
    on conflict (java_uuid) do update set username = excluded.username, last_seen = now()
    returning id
"""


@router.post("/players/upsert")
async def upsert_player(body: PlayerUpsert) -> dict:
    _require_db()
    async with pool().acquire() as conn:
        pid = await conn.fetchval(_UPSERT_PLAYER, uuid.UUID(body.uuid), body.username)
    return {"id": str(pid)}


@router.put("/homes")
async def set_home(body: HomeUpsert) -> dict:
    _require_db()
    async with pool().acquire() as conn:
        async with conn.transaction():
            pid = await conn.fetchval(_UPSERT_PLAYER, uuid.UUID(body.uuid), body.username or "desconocido")
            await conn.execute(
                """
                insert into player_homes (player_id, server, world, x, y, z, yaw, pitch, updated_at)
                values ($1, $2, $3, $4, $5, $6, $7, $8, now())
                on conflict (player_id, server) do update set
                    world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z,
                    yaw = excluded.yaw, pitch = excluded.pitch, updated_at = now()
                """,
                pid, body.server, body.world, body.x, body.y, body.z, body.yaw, body.pitch,
            )
    return {"status": "ok"}


@router.get("/homes/{player_uuid}", response_model=HomeOut)
async def get_home(player_uuid: str, server: str) -> HomeOut:
    _require_db()
    async with pool().acquire() as conn:
        row = await conn.fetchrow(
            """
            select h.server, h.world, h.x, h.y, h.z, h.yaw, h.pitch
            from player_homes h
            join players p on p.id = h.player_id
            where p.java_uuid = $1 and h.server = $2
            """,
            uuid.UUID(player_uuid), server,
        )
    if row is None:
        raise HTTPException(status_code=404, detail="El jugador no tiene casa en este servidor")
    return HomeOut(**dict(row))


# --- Memoria de conversacion de NPC (Fase 5: el NPC te recuerda) ---

@router.post("/npc-memory")
async def append_conversation(body: ConversationAppend) -> dict:
    _require_db()
    async with pool().acquire() as conn:
        await conn.execute(
            "insert into npc_conversations (npc_key, player_uuid, role, content) values ($1, $2, $3, $4)",
            body.npc_key, body.player_uuid, body.role, body.content,
        )
        count = await conn.fetchval(
            "select count(*) from npc_conversations where npc_key = $1 and player_uuid = $2",
            body.npc_key, body.player_uuid,
        )
    return {"status": "ok", "count": count}


@router.get("/npc-memory", response_model=list[ConversationTurn])
async def get_conversation(npc_key: str, player_uuid: str, limit: int = 8) -> list[ConversationTurn]:
    _require_db()
    limit = max(1, min(limit, 50))
    async with pool().acquire() as conn:
        rows = await conn.fetch(
            """
            select role, content from npc_conversations
            where npc_key = $1 and player_uuid = $2
            order by created_at desc
            limit $3
            """,
            npc_key, player_uuid, limit,
        )
    # Cronologico (mas antiguo primero) para reconstruir la conversacion.
    return [ConversationTurn(role=r["role"], content=r["content"]) for r in reversed(rows)]


# Turnos VIEJOS (todos menos los ultimos `keep`): lo que hay que condensar y olvidar.
_OLDER_FILTER = """
    npc_key = $1 and player_uuid = $2
    and id not in (
        select id from npc_conversations
        where npc_key = $1 and player_uuid = $2
        order by created_at desc limit $3
    )
"""


@router.get("/npc-memory/older", response_model=list[ConversationTurn])
async def get_older(npc_key: str, player_uuid: str, keep: int = 10) -> list[ConversationTurn]:
    _require_db()
    async with pool().acquire() as conn:
        rows = await conn.fetch(
            f"select role, content from npc_conversations where {_OLDER_FILTER} order by created_at",
            npc_key, player_uuid, keep,
        )
    return [ConversationTurn(role=r["role"], content=r["content"]) for r in rows]


@router.delete("/npc-memory/older")
async def prune_older(npc_key: str, player_uuid: str, keep: int = 10) -> dict:
    _require_db()
    async with pool().acquire() as conn:
        await conn.execute(
            f"delete from npc_conversations where {_OLDER_FILTER}",
            npc_key, player_uuid, keep,
        )
    return {"status": "ok"}


# --- Ficha evolutiva del jugador (memoria a largo plazo del NPC) ---

@router.get("/npc-summary", response_model=SummaryOut)
async def get_summary(npc_key: str, player_uuid: str) -> SummaryOut:
    _require_db()
    async with pool().acquire() as conn:
        row = await conn.fetchrow(
            "select summary from npc_player_memory where npc_key = $1 and player_uuid = $2",
            npc_key, player_uuid,
        )
    return SummaryOut(summary=row["summary"] if row else "")


@router.put("/npc-summary")
async def put_summary(body: SummaryUpsert) -> dict:
    _require_db()
    async with pool().acquire() as conn:
        await conn.execute(
            """
            insert into npc_player_memory (npc_key, player_uuid, summary, updated_at)
            values ($1, $2, $3, now())
            on conflict (npc_key, player_uuid) do update set summary = excluded.summary, updated_at = now()
            """,
            body.npc_key, body.player_uuid, body.summary,
        )
    return {"status": "ok"}


# --- Economia (Fase 6): cuentas, transferencias y cobro de servicios ---

_STARTING_BALANCE = decimal.Decimal("100.00")
_BANCO = uuid.UUID("00000000-0000-0000-0000-000000000000")  # cuenta del sistema (sumidero)


async def _account(conn, owner_id: uuid.UUID, owner_type: str = "player"):
    """Devuelve (o crea con saldo inicial) la cuenta AET de un propietario."""
    row = await conn.fetchrow(
        "select id, balance from accounts where owner_type = $1 and owner_id = $2 and currency = 'AET'",
        owner_type, owner_id,
    )
    if row is None:
        start = _STARTING_BALANCE if owner_type == "player" else decimal.Decimal(0)
        row = await conn.fetchrow(
            "insert into accounts (owner_type, owner_id, balance, currency) values ($1, $2, $3, 'AET') "
            "returning id, balance",
            owner_type, owner_id, start,
        )
    return row


@router.get("/accounts/{player_uuid}", response_model=BalanceOut)
async def get_balance(player_uuid: str) -> BalanceOut:
    _require_db()
    async with pool().acquire() as conn:
        acc = await _account(conn, uuid.UUID(player_uuid))
    return BalanceOut(balance=float(acc["balance"]))


@router.post("/transfer")
async def transfer(body: TransferIn) -> dict:
    _require_db()
    amount = decimal.Decimal(str(body.amount))
    if amount <= 0:
        raise HTTPException(status_code=400, detail="La cantidad debe ser positiva")
    async with pool().acquire() as conn:
        async with conn.transaction():
            frm = await _account(conn, uuid.UUID(body.from_uuid))
            if frm["balance"] < amount:
                raise HTTPException(status_code=400, detail="Fondos insuficientes")
            to = await _account(conn, uuid.UUID(body.to_uuid))
            await conn.execute("update accounts set balance = balance - $1 where id = $2", amount, frm["id"])
            await conn.execute("update accounts set balance = balance + $1 where id = $2", amount, to["id"])
            await conn.execute(
                "insert into transactions (from_account, to_account, amount, reason) values ($1, $2, $3, $4)",
                frm["id"], to["id"], amount, body.reason or "transferencia",
            )
    return {"status": "ok"}


@router.post("/charge")
async def charge(body: ChargeIn) -> dict:
    """Cobra a un jugador (por un servicio). El dinero va a la cuenta del sistema."""
    _require_db()
    amount = decimal.Decimal(str(body.amount))
    if amount <= 0:
        raise HTTPException(status_code=400, detail="La cantidad debe ser positiva")
    async with pool().acquire() as conn:
        async with conn.transaction():
            acc = await _account(conn, uuid.UUID(body.uuid))
            if acc["balance"] < amount:
                raise HTTPException(status_code=400, detail="Fondos insuficientes")
            banco = await _account(conn, _BANCO, owner_type="system")
            await conn.execute("update accounts set balance = balance - $1 where id = $2", amount, acc["id"])
            await conn.execute("update accounts set balance = balance + $1 where id = $2", amount, banco["id"])
            await conn.execute(
                "insert into transactions (from_account, to_account, amount, reason) values ($1, $2, $3, $4)",
                acc["id"], banco["id"], amount, body.reason or "servicio",
            )
    return {"status": "ok"}


# --- Auditoria de planes (Fase 5: cada plan de la IA queda registrado) ---

@router.post("/plan-audit")
async def record_plan_audit(body: PlanAudit) -> dict:
    _require_db()
    async with pool().acquire() as conn:
        await conn.execute(
            """
            insert into plan_audit (plan_id, actor_type, actor_id, status, rejection_reason, actions)
            values ($1, $2, $3, $4, $5, $6::jsonb)
            """,
            uuid.UUID(body.plan_id), body.actor_type, body.actor_id, body.status,
            body.rejection_reason, json.dumps(body.actions),
        )
    return {"status": "ok"}
