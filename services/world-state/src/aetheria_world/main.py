"""Punto de entrada del World-State (FastAPI)."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

from aetheria_world import __version__
from aetheria_world.api.routes import router
from aetheria_world.db import connect, disconnect, is_ready


@asynccontextmanager
async def lifespan(app: FastAPI):
    await connect()
    yield
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
