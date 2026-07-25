"""Pool de conexiones a Postgres (asyncpg).

El servicio arranca aunque la DB no este disponible: en ese caso el pool queda a None y
los endpoints que la necesitan devuelven 503. Asi /health sigue respondiendo y el
servicio no entra en crash-loop por una DB que aun no esta lista.
"""

from __future__ import annotations

import logging

import asyncpg

from aetheria_world.config import settings

logger = logging.getLogger("aetheria_world.db")

_pool: asyncpg.Pool | None = None


async def connect() -> None:
    global _pool
    try:
        _pool = await asyncpg.create_pool(settings.database_url, min_size=1, max_size=5)
        logger.info("Conectado a la base de datos.")
    except Exception as exc:  # noqa: BLE001 - queremos degradar, no caer
        _pool = None
        logger.warning("No se pudo conectar a la DB (%s). Endpoints de datos daran 503.", exc)


async def disconnect() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def is_ready() -> bool:
    return _pool is not None


def pool() -> asyncpg.Pool:
    if _pool is None:
        raise RuntimeError("Pool de DB no inicializado")
    return _pool
