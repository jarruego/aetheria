"""Cliente hacia el World-State: trae resumenes estructurados como contexto del planner.

Degrada con elegancia: si el world-state no esta disponible, devuelve None y el planner
sigue funcionando sin contexto (nunca cae por esto).
"""

from __future__ import annotations

import httpx

from aetheria_ai.config import settings

_TIMEOUT = httpx.Timeout(10.0, connect=3.0)


async def get_world_summary(world: str) -> dict | None:
    url = f"{settings.world_state_url.rstrip('/')}/internal/world/{world}/summary"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json()
    except Exception:  # noqa: BLE001 - contexto opcional; nunca hacemos caer el planner
        return None
