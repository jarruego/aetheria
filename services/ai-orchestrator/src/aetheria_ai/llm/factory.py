"""Factory que selecciona el proveedor LLM segun la configuracion (ADR-0004, ADR-0007).

- `get_provider()` devuelve el proveedor del Nivel 3 (razonamiento), segun LLM_PROVIDER.
  Por defecto es `stub` (coste cero).
- `get_local_provider()` devuelve el proveedor del Nivel 2 (charla normal), que por
  contrato NUNCA puede ser de pago (ADR-0007). Esa regla se aplica AQUI, en codigo:
  ver `_PAID_PROVIDERS`.
"""

from __future__ import annotations

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMProvider
from aetheria_ai.llm.stub_provider import StubProvider

# Proveedores que facturan por token. Prohibidos en el Nivel 2.
_PAID_PROVIDERS = frozenset({"claude", "openai"})


def _build(provider: str) -> LLMProvider:
    """Instancia un proveedor por nombre. No aplica politica de coste."""
    if provider in ("stub", "local"):
        return StubProvider()

    if provider == "ollama":
        from aetheria_ai.llm.ollama_provider import OllamaProvider

        return OllamaProvider(
            base_url=settings.ollama_base_url,
            timeout_s=settings.ollama_timeout_s,
        )

    if provider == "claude":
        from aetheria_ai.llm.anthropic_provider import AnthropicProvider

        return AnthropicProvider(api_key=settings.anthropic_api_key)

    # Puntos de extension previstos: "openai", etc.
    raise ValueError(f"Proveedor LLM no soportado: {provider!r}")


def get_provider(name: str | None = None) -> LLMProvider:
    """Proveedor del Nivel 3. Admite proveedores de pago si se configuran a proposito."""
    return _build((name or settings.llm_provider or "stub").lower())


def get_local_provider() -> LLMProvider:
    """Proveedor del Nivel 2 (charla normal).

    BLINDAJE DE CARTERA (ADR-0007): el Nivel 2 se dispara en cada frase de cada NPC,
    asi que un proveedor de pago aqui vaciaria la cuenta sin que nadie lo note. Por eso
    se rechaza explicitamente en vez de confiar en que nadie configure mal el entorno.
    """
    provider = (settings.llm_local_provider or "stub").lower()

    if provider in _PAID_PROVIDERS:
        raise ValueError(
            f"LLM_LOCAL_PROVIDER={provider!r} es un proveedor de PAGO y el Nivel 2 "
            "no puede gastar dinero (ADR-0007). Usa 'stub' (simulado) u 'ollama' "
            "(modelo local, gratis)."
        )

    return _build(provider)
