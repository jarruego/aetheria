"""Endpoints de sistema (sin autenticación)."""

from __future__ import annotations

from fastapi import APIRouter

from aetheria_api import __version__
from aetheria_api.schemas import Health

router = APIRouter(tags=["system"])


@router.get("/health", response_model=Health)
async def health() -> Health:
    return Health(version=__version__)
