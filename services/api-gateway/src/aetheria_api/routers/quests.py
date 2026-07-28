"""Misiones y prestigio (0009). Reenvia al World-State, como el resto de la economia.

El plugin nunca toca la base de datos: propone misiones, apunta avances y pide cobrar;
el backend tasa la recompensa y decide si la mision esta cumplida de verdad.
"""

from __future__ import annotations

from urllib.parse import quote

import httpx
from fastapi import APIRouter, Depends, HTTPException

from aetheria_api.config import settings
from aetheria_api.security import require_internal_token

router = APIRouter(prefix="/v1", tags=["quests"])

_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


async def _ws(method: str, path: str, *, json: dict | None = None):
    url = f"{settings.world_state_url.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.request(method, url, json=json)
        if resp.status_code in (400, 404, 409):
            raise HTTPException(status_code=resp.status_code, detail=resp.json().get("detail", "error"))
        resp.raise_for_status()
        return resp.json()


@router.post("/quests", dependencies=[Depends(require_internal_token)])
async def create_quest(body: dict):
    """El pregonero ofrece un encargo del pueblo (objetivo compuesto por codigo)."""
    return await _ws("POST", "/internal/quests", json=body)


@router.get("/quests", dependencies=[Depends(require_internal_token)])
async def list_quests(player: str, village: str):
    """Misiones activas de un jugador en una aldea."""
    return await _ws(
        "GET", f"/internal/quests?player_uuid={quote(player)}&village_name={quote(village)}"
    )


@router.post("/quests/progress", dependencies=[Depends(require_internal_token)])
async def quest_progress(body: dict):
    """Avance de una mision (entrega, venta, charla, donacion...)."""
    return await _ws("POST", "/internal/quests/progress", json=body)


@router.post("/quest/complete", dependencies=[Depends(require_internal_token)])
async def quest_complete(body: dict):
    """Cobra una mision: solo si el backend ve `progress >= target`."""
    return await _ws("POST", "/internal/quests/complete", json=body)


@router.post("/donation", dependencies=[Depends(require_internal_token)])
async def donation(body: dict):
    """Aportacion al arca de una aldea: cobra y suma prestigio en un solo viaje."""
    return await _ws("POST", "/internal/donation", json=body)


@router.get("/village/{village_name}/reputation", dependencies=[Depends(require_internal_token)])
async def village_reputation(village_name: str):
    """Prestigio de los JUGADORES en esa aldea (el plugin lo fusiona con sus aldeanos)."""
    return await _ws("GET", f"/internal/village/{quote(village_name)}/reputation")


@router.get("/players/{player_uuid}/reputation", dependencies=[Depends(require_internal_token)])
async def player_reputation(player_uuid: str):
    """Prestigio de un jugador en todas las aldeas (para /prestigio)."""
    return await _ws("GET", f"/internal/players/{quote(player_uuid)}/reputation")
