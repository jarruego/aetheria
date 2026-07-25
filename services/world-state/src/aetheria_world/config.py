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


settings = Settings()
