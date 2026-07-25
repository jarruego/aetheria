"""Blindaje de coste: ningun test debe llamar nunca a un proveedor LLM de pago.

Fuerza `LLM_PROVIDER=stub` durante los tests, sin importar lo que haya en el .env local.
"""

import pytest

from aetheria_ai.config import settings


@pytest.fixture(autouse=True)
def _force_stub_provider():
    original = settings.llm_provider
    settings.llm_provider = "stub"
    yield
    settings.llm_provider = original
