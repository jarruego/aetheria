"""Factory que selecciona el proveedor LLM segun la configuracion (ADR-0004, ADR-0007).

- `get_provider()` devuelve el proveedor del Nivel 3 (LLM potente), segun LLM_PROVIDER.
  Por defecto es `stub` (coste cero).
- `get_local_provider()` devuelve el proveedor del Nivel 2 (charla normal), SIEMPRE
  local/gratuito por ahora, para no gastar nunca en conversacion cotidiana.
"""

from __future__ import annotations

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMProvider
from aetheria_ai.llm.stub_provider import StubProvider


def get_provider(name: str | None = None) -> LLMProvider:
    provider = (name or settings.llm_provider or "stub").lower()

    if provider in ("stub", "local"):
        return StubProvider()

    if provider == "claude":
        from aetheria_ai.llm.anthropic_provider import AnthropicProvider

        return AnthropicProvider(api_key=settings.anthropic_api_key)

    # Puntos de extension previstos: "openai", etc.
    raise ValueError(f"Proveedor LLM no soportado: {settings.llm_provider!r}")


def get_local_provider() -> LLMProvider:
    """Proveedor del Nivel 2. Hoy siempre stub (gratis); en el futuro, un modelo local."""
    return StubProvider()
