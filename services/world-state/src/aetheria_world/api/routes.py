"""Endpoints internos del World-State: resumenes de solo lectura.

Los consume el API Gateway (y en Fase 3 el planner). Nunca devuelven bloques.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException

from aetheria_world.db import is_ready, pool
from aetheria_world.models import WorldRef, WorldSummary

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
