"""Punto de entrada del World-State (FastAPI)."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from aetheria_world import __version__
from aetheria_world.api.routes import router
from aetheria_world.config import settings
from aetheria_world.db import connect, disconnect, is_ready
from aetheria_world.simulation import simulation_loop


@asynccontextmanager
async def lifespan(app: FastAPI):
    await connect()
    # Fase 8: el mundo evoluciona solo (simulacion por ticks en segundo plano).
    sim_task: asyncio.Task | None = None
    if settings.sim_enabled:
        sim_task = asyncio.create_task(simulation_loop())
    yield
    if sim_task is not None:
        sim_task.cancel()
    await disconnect()


app = FastAPI(
    title="Aetheria World-State",
    version=__version__,
    description="Resumenes estructurados del mundo (read-model) para la IA.",
    lifespan=lifespan,
)

app.include_router(router)


@app.get("/health", tags=["system"])
async def health() -> dict:
    return {
        "status": "ok",
        "service": "world-state",
        "version": __version__,
        "db": is_ready(),
    }
