"""Rutas publicas del mundo. Reenvian al World-State (resumenes de solo lectura)."""

from __future__ import annotations

import httpx
from fastapi import APIRouter, Depends

from aetheria_api.config import settings
from aetheria_api.security import require_internal_token

router = APIRouter(prefix="/v1", tags=["world"])

_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


async def _get_world_state(path: str) -> dict | list:
    url = f"{settings.world_state_url.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.get(url)
        resp.raise_for_status()
        return resp.json()


@router.get("/worlds", dependencies=[Depends(require_internal_token)])
async def worlds():
    return await _get_world_state("/internal/worlds")


@router.get("/world/{key}/summary", dependencies=[Depends(require_internal_token)])
async def world_summary(key: str):
    return await _get_world_state(f"/internal/world/{key}/summary")
