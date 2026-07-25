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

    # --- Nivel 3: razonamiento (ver ADR-0004, ADR-0007) ---
    # Por defecto 'stub' = coste cero. 'ollama' = IA real y gratis (modelo local).
    # 'claude' = IA real DE PAGO (requiere ANTHROPIC_API_KEY).
    llm_provider: str = "stub"  # stub | ollama | claude | openai
    llm_model_l3: str = "claude-sonnet-4-6"

    # --- Nivel 2: charla normal (ADR-0007: NUNCA gasta) ---
    # Solo admite proveedores gratuitos. 'claude' aqui se rechaza a proposito.
    # Modelo INSTRUCT puro a proposito: los de razonamiento (Qwen3) divagan en la
    # charla casual y son mas lentos. Ver ADR-0009.
    llm_local_provider: str = "stub"  # stub | ollama
    llm_model_l2: str = "gemma3:4b"

    # --- Servidor LLM local, compatible OpenAI (ADR-0009) ---
    # OJO: el servicio corre en Docker, donde 'localhost' es el propio contenedor.
    # Para llegar al Ollama del host se usa host.docker.internal.
    # Fuera de Docker (tests, ejecucion directa): http://localhost:11434
    ollama_base_url: str = "http://host.docker.internal:11434"
    ollama_timeout_s: float = 120.0

    anthropic_api_key: str | None = None
    openai_api_key: str | None = None


settings = Settings()
