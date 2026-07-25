"""Tests del enrutador de conversacion de 3 niveles (coste cero: usa el stub)."""

import asyncio

from aetheria_ai.conversation import classify_level, handle_conversation
from aetheria_ai.models.plan import ConversationRequest


def _req(msg: str) -> ConversationRequest:
    return ConversationRequest(npc_id="npc-1", player_id="p1", message=msg)


def test_classify_levels():
    assert classify_level("hola buenas") == 1
    assert classify_level("me gusta tu casa") == 2
    assert classify_level("planifica una plaza con fuentes") == 3
    assert classify_level("x" * 130) == 3


def test_level1_is_deterministic_and_free():
    resp = asyncio.run(handle_conversation(_req("hola")))
    assert resp.level == 1
    assert resp.reply
    assert "[stub:" not in resp.reply  # Nivel 1 no usa proveedor


def test_level2_uses_local_stub():
    resp = asyncio.run(handle_conversation(_req("que tal va todo por aqui")))
    assert resp.level == 2
    assert "[stub:" in resp.reply  # proveedor local gratuito


def test_level3_uses_provider_stub_by_default():
    resp = asyncio.run(handle_conversation(_req("explica como optimizar mi ciudad")))
    assert resp.level == 3
    assert "[stub:" in resp.reply
