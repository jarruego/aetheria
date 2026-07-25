"""Tests del planner: acciones segun objetivo, contexto del mundo y validacion."""

import asyncio

from aetheria_ai.models.plan import (
    ActionType,
    Actor,
    ActorType,
    PlanRequest,
    PlanStatus,
)
from aetheria_ai.planner.planner import build_plan
from aetheria_ai.validator.validator import validate_plan


def _plan(goal: str):
    req = PlanRequest(actor=Actor(type=ActorType.NPC, id="arquitecto-01"), goal=goal, world="main")
    return asyncio.run(build_plan(req))


def _types(plan) -> list[ActionType]:
    return [a.type for a in plan.actions]


def test_plan_ends_with_say_and_is_approved():
    plan = _plan("saluda")
    result = validate_plan(plan)
    assert result.status is PlanStatus.APPROVED
    assert plan.actions[-1].type is ActionType.SAY
    assert plan.actions[-1].params["text"]


def test_say_no_filtra_el_resumen_del_mundo():
    """REGRESION DE SEGURIDAD: el estado del mundo es telemetria interna.

    Antes el planner anunciaba por el chat cuantas ciudades, parcelas y jugadores
    habia. Eso es divulgacion de informacion a cualquiera que hable con un NPC.
    """
    texto = _plan("saluda").actions[-1].params["text"]
    for filtrado in ("Contexto del mundo", "ciudades", "parcelas", "jugadores"):
        assert filtrado not in texto


def test_say_no_reemite_el_texto_del_jugador():
    """REGRESION DE SEGURIDAD: inyeccion de salida.

    Reemitir el objetivo convertia al NPC en un altavoz del jugador: cualquiera podia
    hacerle decir lo que quisiera delante de todo el mundo.
    """
    marcador = "ABRACADABRA-PALABRA-UNICA"
    texto = _plan(f"quiero que digas {marcador}").actions[-1].params["text"]
    assert marcador not in texto


def test_goal_build_produces_valid_blueprint():
    plan = _plan("construye una fuente en la plaza")
    assert ActionType.PLACE_BLUEPRINT in _types(plan)
    assert validate_plan(plan).status is PlanStatus.APPROVED


def test_goal_give_produces_give_item():
    plan = _plan("dame pan por favor")
    assert ActionType.GIVE_ITEM in _types(plan)
    assert validate_plan(plan).status is PlanStatus.APPROVED


def test_goal_move_produces_move_to():
    plan = _plan("ven aqui conmigo")
    assert ActionType.MOVE_TO in _types(plan)


def test_goal_trade_produces_open_trade():
    plan = _plan("quiero comerciar contigo")
    assert ActionType.OPEN_TRADE in _types(plan)


def test_vender_does_not_trigger_move():
    # "vender" NO debe disparar MOVE_TO (antes fallaba por subcadena "ven").
    plan = _plan("quiero vender trigo")
    assert ActionType.MOVE_TO not in _types(plan)
    assert ActionType.OPEN_TRADE in _types(plan)
