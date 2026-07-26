"""Conversacion de NPC: personalidad humana + memoria en dos capas (Fase 5).

- Nivel 1: intents deterministas (despedidas, gracias). Coste cero.
- Nivel 2/3: LLM con personalidad y memoria.

Memoria como la humana:
  * CORTO PLAZO: los ultimos ~10 turnos, verbatim -> al LLM (nunca se satura).
  * LARGO PLAZO: una FICHA del jugador que se re-condensa; al acumularse charla vieja se
    funde en la ficha y se BORRA (se difumina/olvida). No se guardan todas las preguntas.

La consolidacion (resumir lo viejo + podar) corre en segundo plano tras responder, para no
demorar la respuesta.
"""

from __future__ import annotations

import asyncio

from aetheria_ai.config import settings
from aetheria_ai.llm.base import LLMMessage
from aetheria_ai.llm.factory import get_local_provider, get_provider
from aetheria_ai.models.plan import ConversationRequest, ConversationResponse
from aetheria_ai import world_state_client as ws

# Intents deterministas de Nivel 1 (sin IA).
_LEVEL1_INTENTS: dict[str, str] = {
    "adios": "Cuidate, nos vemos por el pueblo.",
    "gracias": "De nada, hombre. Para eso estamos.",
}

_LEVEL3_HINTS = (
    "planifica", "planificar", "disena", "diseña", "construye", "construir",
    "por que", "por qué", "explica", "compara", "estrategia", "optimiza",
)

# Definicion de cada NPC: nombre, caracter, donde esta y en que ayuda.
_NPCS: dict[str, dict[str, str]] = {
    "guia-main": {
        "name": "Bruno",
        "trait": "un herrero robusto, campechano y bromista",
        "where": "junto al portal de esmeralda del lobby, el que lleva al mundo principal",
        "help": "orientar a los viajeros y animarles a cruzar al mundo principal, donde crece la civilizacion",
    },
    "guia-creative": {
        "name": "Mila",
        "trait": "una arquitecta sonadora y entusiasta",
        "where": "junto al portal de diamante del lobby, el que lleva al mundo creativo",
        "help": "invitar a la gente a construir sin limites en el mundo creativo",
    },
    "guia-vuelta": {
        "name": "Tobias",
        "trait": "un cartografo viajero, tranquilo y curioso",
        "where": "junto al portal de vuelta al lobby, en los mundos de juego",
        "help": "ayudar a los viajeros a volver al lobby",
    },
}
_NPCS["guia-creativo"] = _NPCS["guia-creative"]  # alias por si acaso
_DEFAULT_NPC = {
    "name": "Aldo", "trait": "un aldeano cercano y amable",
    "where": "en el pueblo", "help": "echar una mano a quien pase",
}

# Contexto compartido del mundo y del elenco (lo que TODO NPC sabe).
_WORLD = (
    "Aetheria es un pueblo dentro de un servidor de Minecraft. Tiene un LOBBY (una sala "
    "flotante desde donde se viaja), un MUNDO PRINCIPAL (donde crecen la civilizacion y la "
    "economia) y un MUNDO CREATIVO (para construir libremente). Entre ellos se viaja por PORTALES."
)
_ROSTER = (
    "Conoces a los demas del pueblo: Bruno (herrero, junto al portal al mundo principal), "
    "Mila (arquitecta, junto al portal al creativo) y Tobias (cartografo, junto al portal de "
    "vuelta al lobby)."
)
_LIMITS = (
    "PUEDES: charlar, orientar, contar cosas del pueblo y de sus mundos, y senalar los portales. "
    "NO PUEDES: dar objetos, teletransportar a nadie (de eso se encargan los portales), dar "
    "permisos ni cambiar el mundo. Si te piden algo asi, declinalo con amabilidad sin salir del personaje."
)

_WINDOW = 10          # turnos verbatim que ve el LLM (memoria de trabajo)
_CONSOLIDATE_AT = 20  # al llegar a tantos turnos, se condensan los viejos en la ficha
_MAX_SUMMARY = 700    # tope de la ficha (lo viejo se va difuminando)


def _npc(npc_id: str) -> dict[str, str]:
    return _NPCS.get(npc_id, _DEFAULT_NPC)


def _system_prompt(npc_id: str, profile: str) -> str:
    n = _npc(npc_id)
    base = (
        f"Eres {n['name']}, {n['trait']}. Estas {n['where']}. "
        f"{_WORLD} "
        f"Tu papel es {n['help']}. "
        f"{_ROSTER} "
        f"{_LIMITS} "
        "Hablas como una PERSONA de verdad: cercano, natural, con frases cortas y calidas. "
        "NUNCA digas que eres una IA, un modelo, un sistema o un programa; NO hables de "
        f"eficiencia, algoritmos ni simulaciones. Si te preguntan como te llamas, eres {n['name']}. "
        "Responde SIEMPRE en espanol y en 1 o 2 frases."
    )
    if profile:
        base += f"\n\nLo que recuerdas de este jugador (puede ser difuso): {profile}"
    return base


def _match_level1(message: str) -> str | None:
    text = message.strip().lower()
    for keyword, reply in _LEVEL1_INTENTS.items():
        if keyword in text:
            return reply
    return None


def classify_level(message: str) -> int:
    if _match_level1(message) is not None:
        return 1
    text = message.strip().lower()
    if len(text) > 120 or any(h in text for h in _LEVEL3_HINTS):
        return 3
    return 2


async def handle_conversation(request: ConversationRequest) -> ConversationResponse:
    level = classify_level(request.message)
    if level == 1:
        return ConversationResponse(reply=_match_level1(request.message) or "", level=1)

    provider = get_local_provider() if level == 2 else get_provider()
    model = settings.llm_model_l2 if level == 2 else settings.llm_model_l3

    # Memoria: ficha (largo plazo) + ultimos turnos (corto plazo).
    profile = await ws.get_npc_summary(request.npc_id, request.player_id)
    history = await ws.get_npc_history(request.npc_id, request.player_id, _WINDOW)

    messages = [LLMMessage(role="system", content=_system_prompt(request.npc_id, profile))]
    for turn in history:
        role = "assistant" if turn.get("role") == "npc" else "user"
        messages.append(LLMMessage(role=role, content=turn.get("content", "")))
    messages.append(LLMMessage(role="user", content=request.message))

    reply = await provider.complete(messages, model=model, max_tokens=200, temperature=0.8)

    # Guarda el turno; el recuento decide si toca consolidar.
    await ws.append_npc_message(request.npc_id, request.player_id, "player", request.message)
    count = await ws.append_npc_message(request.npc_id, request.player_id, "npc", reply)

    if count >= _CONSOLIDATE_AT:
        # En segundo plano: no demora la respuesta al jugador.
        asyncio.create_task(_consolidate(request.npc_id, request.player_id))

    return ConversationResponse(reply=reply, level=level)


async def _consolidate(npc_id: str, player_id: str) -> None:
    """Funde los turnos viejos en la ficha del jugador y los borra (se difuminan)."""
    older = await ws.get_older_turns(npc_id, player_id, _WINDOW)
    if len(older) < 4:
        return

    previous = await ws.get_npc_summary(npc_id, player_id)
    lines = "\n".join(
        f"{'Jugador' if t.get('role') == 'player' else 'Yo'}: {t.get('content', '')}" for t in older
    )
    system = (
        "Mantienes una FICHA breve de un jugador, vista por un aldeano. Actualiza la ficha "
        "combinando la anterior con la conversacion nueva: quedate con lo importante y estable "
        "(nombre, gustos, oficio, temas recurrentes, tono) y descarta lo trivial y puntual. "
        "Escribela en 2-3 frases, en tercera persona. Devuelve SOLO la ficha."
    )
    user = f"Ficha anterior: {previous or '(vacia)'}\n\nConversacion reciente:\n{lines}\n\nFicha actualizada:"

    try:
        new_summary = await get_local_provider().complete(
            [LLMMessage(role="system", content=system), LLMMessage(role="user", content=user)],
            model=settings.llm_model_l2,
            max_tokens=220,
            temperature=0.3,
        )
    except Exception:  # noqa: BLE001 - si falla el resumen, no tocamos nada
        return

    new_summary = new_summary.strip()[:_MAX_SUMMARY]
    if not new_summary:
        return

    await ws.put_npc_summary(npc_id, player_id, new_summary)
    await ws.prune_older_turns(npc_id, player_id, _WINDOW)  # olvida lo ya condensado
