"""Rutas de estructuras sociales: parcelas (Fase 9). Reenvian al World-State.

El gateway solo transporta y autentica; la logica (solape, cobro en AET, propiedad) vive
en el World-State, dueno de la DB.
"""

from __future__ import annotations

import httpx
from fastapi import APIRouter, Depends, HTTPException

from aetheria_api.config import settings
from aetheria_api.security import require_internal_token

router = APIRouter(prefix="/v1", tags=["social"])

_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


async def _ws(method: str, path: str, *, json: dict | None = None):
    url = f"{settings.world_state_url.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.request(method, url, json=json)
        if resp.status_code in (400, 404, 409):
            raise HTTPException(status_code=resp.status_code, detail=resp.json().get("detail", "error"))
        resp.raise_for_status()
        return resp.json()


@router.post("/claims", dependencies=[Depends(require_internal_token)])
async def claim(body: dict):
    return await _ws("POST", "/internal/plots/claim", json=body)


@router.post("/claims/unclaim", dependencies=[Depends(require_internal_token)])
async def unclaim(body: dict):
    return await _ws("POST", "/internal/plots/unclaim", json=body)


@router.get("/claims", dependencies=[Depends(require_internal_token)])
async def claims(world: str):
    return await _ws("GET", f"/internal/plots?world={world}")
