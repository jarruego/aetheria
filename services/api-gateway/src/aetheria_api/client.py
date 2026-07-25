"""Cliente HTTP hacia el AI Orchestrator.

Aísla el reenvío para que los routers no conozcan detalles de transporte. Si mañana el
orchestrator vive en otra máquina, solo cambia `AI_ORCHESTRATOR_URL`.
"""

from __future__ import annotations

import httpx

from aetheria_api.config import settings

_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


async def post_orchestrator(path: str, payload: dict) -> dict:
    url = f"{settings.ai_orchestrator_url.rstrip('/')}{path}"
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.post(url, json=payload)
        resp.raise_for_status()
        return resp.json()
