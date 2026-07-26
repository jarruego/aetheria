"""Configuracion del World-State desde variables de entorno."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    aetheria_env: str = "dev"
    log_level: str = "INFO"

    world_state_host: str = "0.0.0.0"
    world_state_port: int = 8070

    # En Docker el host es 'postgres'; en prod, la cadena de Supabase.
    database_url: str = "postgresql://aetheria:aetheria-local-dev@localhost:5432/aetheria"

    # Fase 8: simulacion autonoma (el mundo evoluciona aunque no haya nadie conectado).
    sim_enabled: bool = True
    sim_tick_seconds: int = 300      # cada cuanto corre un tick economico
    sim_income_min: float = 5.0      # ingreso minimo por negocio y tick (AET)
    sim_income_max: float = 30.0     # ingreso maximo por negocio y tick (AET)

    # Fase 9: coste de reclamar una parcela (un chunk) en AET.
    claim_price: float = 50.0


settings = Settings()
