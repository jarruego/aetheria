"""Rutas de jugadores y casas. Reenvian al World-State (camino de ESCRITURA, Fase 5)."""

from __future__ import annotations

import httpx
from fastapi import APIRouter, Depends, HTTPException

from aetheria_api.config import settings
from aetheria_api.security import require_internal_token

router = APIRouter(prefix="/v1", tags=["players"])

_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


async def _forward(method: str, path: str, *, json: dict | None = None, params: dict | None = None):
    url = f"{settings.world_state_url.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.request(method, url, json=json, params=params)
        if resp.status_code == 404:
            raise HTTPException(status_code=404, detail=resp.json().get("detail", "no encontrado"))
        resp.raise_for_status()
        return resp.json()


@router.post("/players", dependencies=[Depends(require_internal_token)])
async def upsert_player(body: dict):
    return await _forward("POST", "/internal/players/upsert", json=body)


@router.put("/homes", dependencies=[Depends(require_internal_token)])
async def set_home(body: dict):
    return await _forward("PUT", "/internal/homes", json=body)


@router.get("/homes/{player_uuid}", dependencies=[Depends(require_internal_token)])
async def get_home(player_uuid: str, server: str):
    return await _forward("GET", f"/internal/homes/{player_uuid}", params={"server": server})
