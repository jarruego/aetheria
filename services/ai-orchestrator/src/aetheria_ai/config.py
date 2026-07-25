"""Configuración del AI Orchestrator, cargada desde variables de entorno.

Ningún secreto se hardcodea. Ver `.env.example` en la raíz del repo.
"""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    aetheria_env: str = "dev"
    log_level: str = "INFO"

    ai_orchestrator_host: str = "0.0.0.0"
    ai_orchestrator_port: int = 8090

    # World-State (contexto para el planner). En Docker, nombre de servicio.
    world_state_url: str = "http://world-state:8070"

    # Proveedor LLM desacoplado (ver ADR-0004, ADR-0007).
    # Por defecto 'stub' = coste cero. Cambiar a 'claude' (con API key) para real.
    llm_provider: str = "stub"  # stub | claude | openai | local
    llm_model_l3: str = "claude-sonnet-4-6"
    llm_model_l2: str = "claude-haiku-4-5-20251001"

    anthropic_api_key: str | None = None
    openai_api_key: str | None = None


settings = Settings()
