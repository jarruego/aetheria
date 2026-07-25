"""Punto de entrada del API Gateway (FastAPI)."""

from __future__ import annotations

from fastapi import FastAPI

from aetheria_api import __version__
from aetheria_api.routers import conversation, plans, system, world

app = FastAPI(
    title="Aetheria API Gateway",
    version=__version__,
    description="Contrato REST del plugin. Autentica y reenvía al backend de IA.",
)

app.include_router(system.router)
app.include_router(conversation.router)
app.include_router(plans.router)
app.include_router(world.router)
