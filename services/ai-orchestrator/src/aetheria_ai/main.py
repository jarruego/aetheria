"""Punto de entrada del AI Orchestrator (FastAPI)."""

from __future__ import annotations

from fastapi import FastAPI

from aetheria_ai import __version__
from aetheria_ai.api.routes import router as internal_router

app = FastAPI(
    title="Aetheria AI Orchestrator",
    version=__version__,
    description="Planner + validador + adaptador LLM. La IA propone; el validador dispone.",
)

app.include_router(internal_router)


@app.get("/health", tags=["system"])
async def health() -> dict:
    return {"status": "ok", "service": "ai-orchestrator", "version": __version__}
