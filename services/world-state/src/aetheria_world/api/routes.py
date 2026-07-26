"""Endpoints internos del World-State: resumenes de solo lectura.

Los consume el API Gateway (y en Fase 3 el planner). Nunca devuelven bloques.
"""

from __future__ import annotations

import decimal
import json
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, HTTPException

from aetheria_world.config import settings
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
    PlotClaimIn,
    PlotOut,
    PlotUnclaimIn,
    SummaryOut,
    SummaryUpsert,
    TransferIn,
    WorldEventOut,
    WorldRef,
    WorldSummary,
)
from aetheria_world.simulation import collect_rent as sim_collect_rent
from aetheria_world.simulation import prosperity as sim_prosperity
from aetheria_world.simulation import run_tick

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


@router.post("/reward")
async def reward(body: ChargeIn) -> dict:
    """Recompensa a un jugador (trabajo/venta): el dinero sale de la cuenta del sistema."""
    _require_db()
    amount = decimal.Decimal(str(body.amount))
    if amount <= 0:
        raise HTTPException(status_code=400, detail="La cantidad debe ser positiva")
    async with pool().acquire() as conn:
        async with conn.transaction():
            acc = await _account(conn, uuid.UUID(body.uuid))
            banco = await _account(conn, _BANCO, owner_type="system")
            await conn.execute("update accounts set balance = balance - $1 where id = $2", amount, banco["id"])
            await conn.execute("update accounts set balance = balance + $1 where id = $2", amount, acc["id"])
            await conn.execute(
                "insert into transactions (from_account, to_account, amount, reason) values ($1, $2, $3, $4)",
                banco["id"], acc["id"], amount, body.reason or "recompensa",
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


# --- Fase 8: el mundo evoluciona solo (simulacion + cronica) ---

@router.get("/world-events", response_model=list[WorldEventOut])
async def world_events(limit: int = 20) -> list[WorldEventOut]:
    """Cronica de sucesos autonomos, del mas reciente al mas antiguo."""
    _require_db()
    rows = await pool().fetch(
        "select kind, description, created_at from world_events order by created_at desc limit $1",
        max(1, min(limit, 100)),
    )
    return [
        WorldEventOut(kind=r["kind"], description=r["description"], created_at=r["created_at"].isoformat())
        for r in rows
    ]


@router.post("/sim/tick")
async def sim_tick() -> dict:
    """Fuerza un tick de simulacion (util para pruebas o para un cron externo)."""
    _require_db()
    async with pool().acquire() as conn:
        async with conn.transaction():
            return await run_tick(conn)


@router.get("/world/prosperity")
async def world_prosperity() -> dict:
    """Estado del pueblo (prospero/estable/en apuros) segun la riqueza de sus negocios."""
    _require_db()
    async with pool().acquire() as conn:
        return await sim_prosperity(conn)


# --- Fase 9: estructuras sociales (parcelas reclamables, con propietario y proteccion) ---

async def _world_id(conn, key: str):
    """Id del mundo por su clave; lo crea si no existe (soporta 'creative', etc.)."""
    row = await conn.fetchrow("select id from worlds where key = $1", key)
    if row is None:
        row = await conn.fetchrow(
            "insert into worlds (key, display_name, persistent) values ($1, $2, true) returning id",
            key, key.capitalize(),
        )
    return row["id"]


async def _player_id(conn, java_uuid: str, name: str | None):
    """Id interno del jugador por su java_uuid; lo registra si hace falta."""
    pid = uuid.UUID(java_uuid)
    row = await conn.fetchrow("select id from players where java_uuid = $1", pid)
    if row is None:
        row = await conn.fetchrow(
            "insert into players (java_uuid, username) values ($1, $2) "
            "on conflict (java_uuid) do update set last_seen = now() returning id",
            pid, name or "desconocido",
        )
    return row["id"]


@router.post("/plots/claim")
async def claim_plot(body: PlotClaimIn) -> dict:
    """Reclama una parcela (un chunk), en compra fija o en alquiler. Cobra en AET."""
    _require_db()
    if body.rental:
        up_front = decimal.Decimal(str(settings.claim_rent_deposit))
        rent = decimal.Decimal(str(settings.claim_rent))
        rent_due = datetime.now(timezone.utc) + timedelta(seconds=settings.rent_interval_seconds)
        reason = "deposito alquiler parcela"
    else:
        up_front = decimal.Decimal(str(settings.claim_price))
        rent = decimal.Decimal(0)
        rent_due = None
        reason = "comprar parcela"

    async with pool().acquire() as conn:
        async with conn.transaction():
            world_id = await _world_id(conn, body.world)
            overlap = await conn.fetchrow(
                """
                select 1 from plots
                where world_id = $1
                  and not ($3 < min_x or $2 > max_x or $5 < min_z or $4 > max_z)
                limit 1
                """,
                world_id, body.min_x, body.max_x, body.min_z, body.max_z,
            )
            if overlap is not None:
                raise HTTPException(status_code=409, detail="Esa parcela ya esta reclamada")

            player_id = await _player_id(conn, body.owner_uuid, body.owner_name)
            acc = await _account(conn, uuid.UUID(body.owner_uuid))
            if acc["balance"] < up_front:
                raise HTTPException(status_code=400, detail="Fondos insuficientes para reclamar")
            banco = await _account(conn, _BANCO, owner_type="system")
            await conn.execute("update accounts set balance = balance - $1 where id = $2", up_front, acc["id"])
            await conn.execute("update accounts set balance = balance + $1 where id = $2", up_front, banco["id"])
            await conn.execute(
                "insert into transactions (from_account, to_account, amount, reason) values ($1, $2, $3, $4)",
                acc["id"], banco["id"], up_front, reason,
            )
            await conn.execute(
                "insert into plots (world_id, owner_id, min_x, min_z, max_x, max_z, base_y, "
                "rental, rent, rent_due) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)",
                world_id, player_id, body.min_x, body.min_z, body.max_x, body.max_z, body.base_y,
                body.rental, rent, rent_due,
            )
            modo = "en alquiler" if body.rental else "en propiedad"
            await conn.execute(
                "insert into world_events (kind, description) values ($1, $2)",
                "social",
                f"{body.owner_name or 'Alguien'} reclamo una parcela {modo} en {body.world}.",
            )
    return {"status": "ok", "price": float(up_front), "rental": body.rental, "rent": float(rent)}


@router.post("/plots/unclaim")
async def unclaim_plot(body: PlotUnclaimIn) -> dict:
    """Libera una parcela propia (identificada por su esquina min). Sin reembolso."""
    _require_db()
    async with pool().acquire() as conn:
        world_id = await _world_id(conn, body.world)
        deleted = await conn.execute(
            """
            delete from plots
            where world_id = $1 and min_x = $2 and min_z = $3
              and owner_id = (select id from players where java_uuid = $4)
            """,
            world_id, body.min_x, body.min_z, uuid.UUID(body.owner_uuid),
        )
    if deleted.endswith("0"):
        raise HTTPException(status_code=404, detail="No tienes una parcela ahi")
    return {"status": "ok"}


@router.get("/plots", response_model=list[PlotOut])
async def list_plots(world: str) -> list[PlotOut]:
    """Todas las parcelas de un mundo (el plugin lo usa para su cache de proteccion)."""
    _require_db()
    async with pool().acquire() as conn:
        world_id = await _world_id(conn, world)
        rows = await conn.fetch(
            """
            select p.min_x, p.min_z, p.max_x, p.max_z, p.base_y, p.rental, p.rent,
                   pl.java_uuid, pl.username
            from plots p left join players pl on pl.id = p.owner_id
            where p.world_id = $1
            """,
            world_id,
        )
    return [
        PlotOut(
            owner_uuid=str(r["java_uuid"]) if r["java_uuid"] else None,
            owner_name=r["username"],
            world=world, min_x=r["min_x"], min_z=r["min_z"], max_x=r["max_x"], max_z=r["max_z"],
            base_y=r["base_y"] if r["base_y"] is not None else 64,
            rental=r["rental"], rent=float(r["rent"]),
        )
        for r in rows
    ]


@router.post("/plots/collect-rent")
async def collect_rent_endpoint() -> dict:
    """Cobra la renta de las parcelas de alquiler vencidas (manual; el bucle ya lo hace)."""
    _require_db()
    async with pool().acquire() as conn:
        async with conn.transaction():
            return await sim_collect_rent(conn)
