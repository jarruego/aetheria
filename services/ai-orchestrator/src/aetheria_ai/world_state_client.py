"""Cliente hacia el World-State: trae resumenes estructurados como contexto del planner.

Degrada con elegancia: si el world-state no esta disponible, devuelve None y el planner
sigue funcionando sin contexto (nunca cae por esto).
"""

from __future__ import annotations

import httpx

from aetheria_ai.config import settings

_TIMEOUT = httpx.Timeout(10.0, connect=3.0)


def _base() -> str:
    return settings.world_state_url.rstrip("/")


async def get_world_summary(world: str) -> dict | None:
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(f"{_base()}/internal/world/{world}/summary")
            resp.raise_for_status()
            return resp.json()
    except Exception:  # noqa: BLE001 - contexto opcional; nunca hacemos caer el planner
        return None


async def get_npc_history(npc_key: str, player_uuid: str, limit: int = 8) -> list[dict]:
    """Recupera los ultimos turnos de conversacion (para que el NPC recuerde)."""
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(
                f"{_base()}/internal/npc-memory",
                params={"npc_key": npc_key, "player_uuid": player_uuid, "limit": limit},
            )
            resp.raise_for_status()
            return resp.json()
    except Exception:  # noqa: BLE001 - memoria opcional; nunca hace caer la conversacion
        return []


async def append_npc_message(npc_key: str, player_uuid: str, role: str, content: str) -> None:
    """Guarda un turno de conversacion. Silencioso: un fallo no debe cortar la charla."""
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            await client.post(
                f"{_base()}/internal/npc-memory",
                json={"npc_key": npc_key, "player_uuid": player_uuid, "role": role, "content": content},
            )
    except Exception:  # noqa: BLE001
        pass


async def record_plan_audit(audit: dict) -> None:
    """Registra un plan (aprobado/rechazado) en la auditoria. Silencioso."""
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            await client.post(f"{_base()}/internal/plan-audit", json=audit)
    except Exception:  # noqa: BLE001
        pass
