"""Tests del proveedor LLM local (Ollama) y del blindaje de cartera del Nivel 2.

Ningun test hace red: el proveedor se ejercita contra un transporte httpx simulado.
Se usa `asyncio.run(...)` en tests sincronos, igual que test_conversation.py, porque
el proyecto no depende de pytest-asyncio. Ver ADR-0009.
"""

from __future__ import annotations

import asyncio

import httpx
import pytest

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMMessage
from aetheria_ai.llm.factory import get_local_provider, get_provider
from aetheria_ai.llm.ollama_provider import OllamaProvider, _strip_reasoning
from aetheria_ai.llm.stub_provider import StubProvider


def _run_with_transport(monkeypatch, handler, *, model: str = "qwen3:4b") -> str:
    """Ejecuta OllamaProvider.complete() contra un servidor simulado.

    Sustituye el constructor de httpx.AsyncClient para inyectar un MockTransport,
    de modo que no sale ni un paquete a la red.
    """
    transport = httpx.MockTransport(handler)
    original_init = httpx.AsyncClient.__init__

    def _patched(self, *args, **kwargs):
        kwargs["transport"] = transport
        original_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "__init__", _patched)

    provider = OllamaProvider(base_url="http://fake:11434")
    return asyncio.run(
        provider.complete([LLMMessage(role="user", content="hola")], model=model)
    )


# --------------------------------------------------------------------------
# Limpieza del razonamiento interno (<think>)
# --------------------------------------------------------------------------


def test_strip_reasoning_elimina_bloque_think():
    crudo = "<think>Deberia responder en espanol.</think>Hola, viajero."
    assert _strip_reasoning(crudo) == "Hola, viajero."


def test_strip_reasoning_soporta_multilinea_y_varios_bloques():
    crudo = "<think>\nuno\ndos\n</think>A<think>tres</think>B"
    assert _strip_reasoning(crudo) == "AB"


def test_strip_reasoning_respeta_texto_sin_think():
    assert _strip_reasoning("  Un plan sin razonamiento.  ") == "Un plan sin razonamiento."


def test_strip_reasoning_no_rompe_json_del_plan():
    """El planner espera JSON: el <think> previo no debe dejar basura delante."""
    crudo = '<think>El jugador pide una casa</think>{"actions": [{"type": "SAY"}]}'
    assert _strip_reasoning(crudo).startswith("{")


# --------------------------------------------------------------------------
# Proveedor Ollama (transporte simulado, sin red)
# --------------------------------------------------------------------------


def test_complete_devuelve_texto_y_limpia_razonamiento(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/chat/completions"
        return httpx.Response(
            200,
            json={
                "choices": [
                    {"message": {"content": "<think>mmm</think>Bienvenido a Aetheria."}}
                ]
            },
        )

    assert _run_with_transport(monkeypatch, handler) == "Bienvenido a Aetheria."


def test_error_de_conexion_explica_host_docker_internal(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    with pytest.raises(RuntimeError, match="host.docker.internal"):
        _run_with_transport(monkeypatch, handler)


def test_modelo_inexistente_sugiere_ollama_pull(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, text='{"error":"model not found"}')

    with pytest.raises(RuntimeError, match="ollama pull qwen3:8b"):
        _run_with_transport(monkeypatch, handler, model="qwen3:8b")


def test_respuesta_malformada_da_error_claro(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"inesperado": True})

    with pytest.raises(RuntimeError, match="Respuesta inesperada"):
        _run_with_transport(monkeypatch, handler)


# --------------------------------------------------------------------------
# Factory: seleccion y blindaje de cartera (ADR-0007)
# --------------------------------------------------------------------------


def test_factory_construye_ollama_para_nivel_3():
    provider = get_provider("ollama")
    assert isinstance(provider, OllamaProvider)
    assert provider.name == "ollama"


def test_nivel_2_acepta_ollama():
    settings.llm_local_provider = "ollama"
    try:
        assert isinstance(get_local_provider(), OllamaProvider)
    finally:
        settings.llm_local_provider = "stub"


def test_nivel_2_por_defecto_es_stub():
    assert isinstance(get_local_provider(), StubProvider)


@pytest.mark.parametrize("pago", ["claude", "openai", "CLAUDE"])
def test_nivel_2_rechaza_proveedores_de_pago(pago):
    """BLINDAJE DE CARTERA: el Nivel 2 se dispara en cada frase de cada NPC."""
    settings.llm_local_provider = pago
    try:
        with pytest.raises(ValueError, match="PAGO"):
            get_local_provider()
    finally:
        settings.llm_local_provider = "stub"
