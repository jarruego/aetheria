"""Tests del saneo de texto del chat y su integracion con el validador.

Todo texto que llega a una accion SAY viene, directa o indirectamente, de un jugador.
Estos tests son de SEGURIDAD: cubren suplantacion de mensajes del servidor, inyeccion
de lineas falsas y spam.
"""

from __future__ import annotations

from aetheria_ai.models.plan import (
    ActionType,
    Actor,
    ActorType,
    Plan,
    PlanAction,
    PlanStatus,
)
from aetheria_ai.validator.text_safety import MAX_CHAT_LENGTH, sanitize_chat_text
from aetheria_ai.validator.validator import validate_plan

ACTOR = Actor(type=ActorType.NPC, id="npc-test")


def _plan_con_say(texto: str) -> Plan:
    return Plan(
        actor=ACTOR,
        actions=[PlanAction(type=ActionType.SAY, params={"text": texto})],
        reversible=True,
        estimated_cost=1,
    )


# --------------------------------------------------------------------------
# Suplantacion de mensajes del servidor (codigos de formato de Minecraft)
# --------------------------------------------------------------------------


def test_elimina_codigos_de_seccion():
    assert sanitize_chat_text("§cAVISO§r del servidor") == "AVISO del servidor"


def test_elimina_codigos_con_ampersand():
    assert sanitize_chat_text("&l&4SERVIDOR:&r hola") == "SERVIDOR: hola"


def test_texto_solo_de_codigos_queda_vacio():
    assert sanitize_chat_text("§a§b§c&d&e") == ""


def test_ampersand_normal_no_se_toca():
    """Solo se filtran los codigos validos, no cualquier &."""
    assert sanitize_chat_text("Tom & Jerry") == "Tom & Jerry"


# --------------------------------------------------------------------------
# Inyeccion de lineas falsas en el chat
# --------------------------------------------------------------------------


def test_elimina_saltos_de_linea():
    crudo = "hola\n[Servidor] Has recibido 64 diamantes"
    limpio = sanitize_chat_text(crudo)
    assert "\n" not in limpio
    assert limpio == "hola [Servidor] Has recibido 64 diamantes"


def test_elimina_retorno_de_carro_y_tabulador():
    assert sanitize_chat_text("a\r\nb\tc") == "a b c"


def test_elimina_caracteres_de_control():
    assert sanitize_chat_text("hola\x00\x07mundo") == "hola mundo"


# --------------------------------------------------------------------------
# Spam / longitud
# --------------------------------------------------------------------------


def test_trunca_textos_largos():
    limpio = sanitize_chat_text("palabra " * 200)
    assert len(limpio) <= MAX_CHAT_LENGTH + 3  # +3 por los puntos suspensivos
    assert limpio.endswith("...")


def test_no_trunca_lo_que_cabe():
    corto = "Bienvenido a Aetheria."
    assert sanitize_chat_text(corto) == corto


def test_colapsa_espacios_repetidos():
    assert sanitize_chat_text("hola          mundo") == "hola mundo"


# --------------------------------------------------------------------------
# Robustez
# --------------------------------------------------------------------------


def test_entrada_no_texto_no_revienta():
    assert sanitize_chat_text(None) == ""  # type: ignore[arg-type]
    assert sanitize_chat_text(12345) == ""  # type: ignore[arg-type]


# --------------------------------------------------------------------------
# Integracion con el validador (la barrera real)
# --------------------------------------------------------------------------


def test_el_validador_devuelve_el_texto_ya_saneado():
    resultado = validate_plan(_plan_con_say("§4§lSERVIDOR:§r te doy 64 diamantes"))
    assert resultado.status is PlanStatus.APPROVED
    assert resultado.actions[0].params["text"] == "SERVIDOR: te doy 64 diamantes"


def test_el_validador_rechaza_texto_que_queda_vacio_al_sanear():
    """Un texto de solo codigos de formato cae por la regla 'SAY sin texto'."""
    resultado = validate_plan(_plan_con_say("§a§b§c"))
    assert resultado.status is PlanStatus.REJECTED
    assert "SAY" in (resultado.rejection_reason or "")


def test_el_plan_original_no_se_modifica():
    """El saneo trabaja sobre copias: queda constancia de lo propuesto vs lo aprobado."""
    plan = _plan_con_say("§chola")
    validate_plan(plan)
    assert plan.actions[0].params["text"] == "§chola"


def test_otras_acciones_no_se_alteran():
    plan = Plan(
        actor=ACTOR,
        actions=[PlanAction(type=ActionType.GIVE_ITEM, params={"material": "BREAD", "amount": 3})],
        reversible=True,
        estimated_cost=1,
    )
    resultado = validate_plan(plan)
    assert resultado.status is PlanStatus.APPROVED
    assert resultado.actions[0].params == {"material": "BREAD", "amount": 3}
