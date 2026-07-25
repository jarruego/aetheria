"""Blindaje de coste y aislamiento: ningun test llama a un proveedor LLM externo.

- `LLM_PROVIDER=stub` evita cualquier llamada a una API de PAGO.
- `LLM_LOCAL_PROVIDER=stub` evita ademas depender de un Ollama arrancado en la
  maquina: seria gratis, pero haria los tests lentos y frangibles.
"""

import pytest

from aetheria_ai.config import settings


@pytest.fixture(autouse=True)
def _force_stub_provider():
    original = (settings.llm_provider, settings.llm_local_provider)
    settings.llm_provider = "stub"
    settings.llm_local_provider = "stub"
    yield
    settings.llm_provider, settings.llm_local_provider = original
