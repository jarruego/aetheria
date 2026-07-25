"""Configuración del API Gateway desde variables de entorno."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    aetheria_env: str = "dev"
    log_level: str = "INFO"

    api_gateway_host: str = "0.0.0.0"
    api_gateway_port: int = 8080

    # Token compartido servicio-a-servicio (plugin -> gateway).
    internal_service_token: str = "changeme-generate-a-long-random-secret"

    # URLs de los servicios internos (en docker-compose se resuelven por nombre).
    ai_orchestrator_url: str = "http://ai-orchestrator:8090"
    world_state_url: str = "http://world-state:8070"


settings = Settings()
