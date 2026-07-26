"""Rutas de economia. Reenvian al World-State (Fase 6)."""

from __future__ import annotations

import httpx
from fastapi import APIRouter, Depends, HTTPException

from aetheria_api.config import settings
from aetheria_api.security import require_internal_token

router = APIRouter(prefix="/v1", tags=["economy"])

_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


async def _ws(method: str, path: str, *, json: dict | None = None):
    url = f"{settings.world_state_url.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.request(method, url, json=json)
        if resp.status_code in (400, 404):
            raise HTTPException(status_code=resp.status_code, detail=resp.json().get("detail", "error"))
        resp.raise_for_status()
        return resp.json()


@router.get("/balance/{player_uuid}", dependencies=[Depends(require_internal_token)])
async def balance(player_uuid: str):
    return await _ws("GET", f"/internal/accounts/{player_uuid}")


@router.post("/pay", dependencies=[Depends(require_internal_token)])
async def pay(body: dict):
    return await _ws("POST", "/internal/transfer", json=body)


@router.post("/reward", dependencies=[Depends(require_internal_token)])
async def reward(body: dict):
    """Recompensa a un jugador por trabajo o venta (Jobs/Shop)."""
    return await _ws("POST", "/internal/reward", json=body)


@router.get("/world-events", dependencies=[Depends(require_internal_token)])
async def world_events(limit: int = 20):
    """Cronica del mundo (Fase 8): que ha pasado mientras no estabas."""
    return await _ws("GET", f"/internal/world-events?limit={limit}")


@router.get("/prosperity", dependencies=[Depends(require_internal_token)])
async def prosperity():
    """Estado de prosperidad del pueblo (para mostrarlo en el HUD)."""
    return await _ws("GET", "/internal/world/prosperity")
