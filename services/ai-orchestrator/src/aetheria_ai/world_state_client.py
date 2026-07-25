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


async def append_npc_message(npc_key: str, player_uuid: str, role: str, content: str) -> int:
    """Guarda un turno de conversacion y devuelve el recuento total (0 si falla)."""
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.post(
                f"{_base()}/internal/npc-memory",
                json={"npc_key": npc_key, "player_uuid": player_uuid, "role": role, "content": content},
            )
            resp.raise_for_status()
            return int(resp.json().get("count", 0))
    except Exception:  # noqa: BLE001
        return 0


async def get_older_turns(npc_key: str, player_uuid: str, keep: int) -> list[dict]:
    """Turnos VIEJOS (todos menos los ultimos `keep`), para condensarlos en la ficha."""
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(
                f"{_base()}/internal/npc-memory/older",
                params={"npc_key": npc_key, "player_uuid": player_uuid, "keep": keep},
            )
            resp.raise_for_status()
            return resp.json()
    except Exception:  # noqa: BLE001
        return []


async def prune_older_turns(npc_key: str, player_uuid: str, keep: int) -> None:
    """Borra los turnos viejos ya condensados (se difuminan/olvidan). Silencioso."""
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            await client.delete(
                f"{_base()}/internal/npc-memory/older",
                params={"npc_key": npc_key, "player_uuid": player_uuid, "keep": keep},
            )
    except Exception:  # noqa: BLE001
        pass


async def get_npc_summary(npc_key: str, player_uuid: str) -> str:
    """Ficha evolutiva del jugador (memoria a largo plazo del NPC). '' si no hay."""
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(
                f"{_base()}/internal/npc-summary",
                params={"npc_key": npc_key, "player_uuid": player_uuid},
            )
            resp.raise_for_status()
            return resp.json().get("summary", "")
    except Exception:  # noqa: BLE001
        return ""


async def put_npc_summary(npc_key: str, player_uuid: str, summary: str) -> None:
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            await client.put(
                f"{_base()}/internal/npc-summary",
                json={"npc_key": npc_key, "player_uuid": player_uuid, "summary": summary},
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
