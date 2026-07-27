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

    # Pueblo vivo: la poblacion crece/mengua con la prosperidad (limites).
    # Arranca en 2 (dos aldeanos fundadores) y crece solo si el pueblo prospera.
    sim_min_population: int = 2
    # Techo = 8 aldeas x 8 vecinos (ver PER_TOWN/MAX_TOWNS en el plugin): el mundo lleno.
    sim_max_population: int = 64

    # Fase 9: reclamar una parcela (un chunk).
    claim_price: float = 50.0            # compra fija (para siempre)
    claim_rent_deposit: float = 10.0     # deposito al alquilar
    claim_rent: float = 5.0              # renta cobrada cada periodo
    rent_interval_seconds: int = 3600    # cada cuanto se cobra la renta ("un dia")


settings = Settings()
