"""Factory que selecciona el proveedor LLM según la configuración (ADR-0004)."""

from __future__ import annotations

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMProvider


def get_provider() -> LLMProvider:
    provider = settings.llm_provider.lower()

    if provider == "claude":
        from aetheria_ai.llm.anthropic_provider import AnthropicProvider

        return AnthropicProvider(api_key=settings.anthropic_api_key)

    # Puntos de extensión previstos (aún no implementados):
    #   if provider == "openai":  from ...openai_provider import OpenAIProvider ...
    #   if provider == "local":   from ...local_provider import LocalProvider ...

    raise ValueError(f"Proveedor LLM no soportado: {settings.llm_provider!r}")
