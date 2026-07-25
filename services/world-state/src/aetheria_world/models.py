"""Esquemas de salida del World-State (resumenes, no bloques)."""

from __future__ import annotations

from pydantic import BaseModel


class WorldRef(BaseModel):
    key: str
    display_name: str
    persistent: bool


class WorldSummary(BaseModel):
    """Resumen estructurado de un mundo. Esto es lo que consume la IA como contexto."""

    world: str
    display_name: str
    cities: int
    plots: int
    plots_owned: int
    npcs: int
    players_total: int
