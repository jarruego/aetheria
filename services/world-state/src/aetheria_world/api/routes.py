"""Endpoints internos del World-State: resumenes de solo lectura.

Los consume el API Gateway (y en Fase 3 el planner). Nunca devuelven bloques.
"""

from __future__ import annotations

import json
import uuid

from fastapi import APIRouter, HTTPException

from aetheria_world.db import is_ready, pool
from aetheria_world.models import (
    ConversationAppend,
    ConversationTurn,
    HomeOut,
    HomeUpsert,
    PlanAudit,
    PlayerUpsert,
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
    return {"status": "ok"}


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
